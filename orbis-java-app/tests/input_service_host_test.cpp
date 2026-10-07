// Drives the real orbis_input_service.cpp against scripted keyboard batches (input_service_host_fakes.c).
#include "orbis_input_service.h"

#include <cstdio>
#include <cstdlib>
#include <string>
#include <unistd.h>

extern "C" void fake_push_batch(int records, const uint64_t *stamps, const uint16_t keys[][4]);
extern "C" void fake_push_slot_batch(int slot, int records, const uint64_t *stamps, const uint16_t keys[][4]);
extern "C" void fake_set_slot1_state(uint64_t stamp, const uint16_t *keys);
extern "C" int fake_slot_reads(int slot);
extern "C" void fake_mouse_move(int samples);

static std::string drain(int ms) {
    std::string out;
    for (int waited = 0; waited < ms; waited += 20) {
        orbis_input_event event{};
        while (orbis_input_read_event(ORBIS_INPUT_FOCUS_CONSOLE, &event, 20) == 0) {
            if (event.type == ORBIS_INPUT_EVENT_KEY_DOWN) out += "D" + std::to_string(event.raw_key) + " ";
            else if (event.type == ORBIS_INPUT_EVENT_KEY_UP) out += "U" + std::to_string(event.raw_key) + " ";
            else if (event.type == ORBIS_INPUT_EVENT_TEXT) out += std::string(1, static_cast<char>(event.unicode_scalar)) + " ";
        }
    }
    return out;
}

static int failures;
static void expect(const std::string &got, const std::string &want, const char *what) {
    if (got == want) return;
    std::printf("FAIL %s: got [%s] want [%s]\n", what, got.c_str(), want.c_str());
    ++failures;
}

int main() {
    if (orbis_input_start() != 0) { std::puts("start failed"); return 1; }
    usleep(50000);
    drain(100);

    // A tap whose press and release came in one read with the same timestamp.
    {
        const uint64_t stamps[] = {1000000, 1000000};
        const uint16_t keys[][4] = {{4, 0}, {0}};
        fake_push_batch(2, stamps, keys);
        expect(drain(900), "D4 a U4 ", "same-stamp tap: press and release, no repeat");
    }
    // Two overlapping keys, all four changes stamped alike.
    {
        const uint64_t stamps[] = {2000000, 2000000, 2000000, 2000000};
        const uint16_t keys[][4] = {{5, 0}, {5, 6, 0}, {6, 0}, {0}};
        fake_push_batch(4, stamps, keys);
        expect(drain(900), "D5 b D6 c U5 U6 ", "same-stamp rollover");
    }
    // Ordinary increasing stamps still work, and an older record (a replay) is ignored.
    {
        const uint64_t stamps[] = {3000000, 3008000};
        const uint16_t keys[][4] = {{7, 0}, {0}};
        fake_push_batch(2, stamps, keys);
        const uint64_t old[] = {2900000};
        const uint16_t old_keys[][4] = {{8, 0}};
        fake_push_batch(1, old, old_keys);
        expect(drain(900), "D7 d U7 ", "increasing stamps; older record skipped");
    }
    // A key held past 500 ms repeats.
    {
        const uint64_t stamps[] = {4000000};
        const uint16_t keys[][4] = {{9, 0}};
        fake_push_batch(1, stamps, keys);
        std::string held = drain(700);
        const uint64_t up[] = {4700000};
        const uint16_t none[][4] = {{0}};
        fake_push_batch(1, up, none);
        held += drain(200);
        bool repeated = held.rfind("D9 f f ", 0) == 0 && held.size() > 12 && held.substr(held.size() - 3) == "U9 ";
        if (!repeated) { std::printf("FAIL held key repeats: [%s]\n", held.c_str()); ++failures; }
    }
    // A keyboard on slot 1 (slot 0 silent): its first key arrives by ReadState, and the slot becomes
    // primary without replaying what its buffered queue held from before.
    {
        const uint64_t stale[] = {100, 200};
        const uint16_t stale_keys[][4] = {{10, 0}, {0}};
        fake_push_slot_batch(1, 2, stale, stale_keys);
        const uint16_t down[] = {11, 0};
        fake_set_slot1_state(5000000, down);
        std::string got = drain(400);
        const uint16_t none[] = {0};
        fake_set_slot1_state(5100000, none);
        const uint64_t up[] = {5100000};
        const uint16_t up_keys[][4] = {{0}};
        fake_push_slot_batch(1, 1, up, up_keys);
        got += drain(300);
        expect(got, "D11 h U11 ", "slot 1 key promotes the slot, stale queue not replayed");
    }
    // Then slot 1 is read every pass through the buffered call: a tap inside one read is kept.
    {
        int before = fake_slot_reads(1);
        const uint64_t stamps[] = {6000000, 6000000};
        const uint16_t keys[][4] = {{12, 0}, {0}};
        fake_push_slot_batch(1, 2, stamps, keys);
        expect(drain(700), "D12 i U12 ", "slot 1 buffered tap");
        int reads = fake_slot_reads(1) - before;
        if (reads < 30) { std::printf("FAIL slot 1 read every pass: %d reads in 700 ms\n", reads); ++failures; }
    }
    // W held (repeating) while the mouse streams motion and the application does not read for 1.2 s:
    // the motions collapse into one around the key events, and no movement is lost.
    {
        orbis_input_state before{};
        before.abi_version = ORBIS_INPUT_ABI_VERSION;
        before.struct_size = sizeof(before);
        orbis_input_get_state(&before);
        const uint64_t down[] = {7000000};
        const uint16_t w[][4] = {{26, 0}};
        fake_push_slot_batch(1, 1, down, w);
        for (int i = 0; i < 120; ++i) { fake_mouse_move(2); usleep(10000); }
        const uint64_t up[] = {8300000};
        const uint16_t none[][4] = {{0}};
        fake_push_slot_batch(1, 1, up, none);
        usleep(100000);
        int motions = 0, repeats = 0, last_x = -1, total = 0;
        orbis_input_event event{};
        while (orbis_input_read_event(ORBIS_INPUT_FOCUS_CONSOLE, &event, 20) == 0) {
            ++total;
            if (event.type == ORBIS_INPUT_EVENT_MOTION) { ++motions; last_x = event.x; }
            if (event.type == ORBIS_INPUT_EVENT_TEXT && event.unicode_scalar == 'w') ++repeats;
        }
        int expected_x = before.x + 240 > 1279 ? 1279 : before.x + 240;
        if (motions > 2 || last_x != expected_x || repeats < 15) {
            std::printf("FAIL mouse with held key: %d events, %d motions, last x %d (want %d), %d w\n",
                        total, motions, last_x, expected_x, repeats);
            ++failures;
        }
    }
    orbis_input_stop();
    if (failures) return 1;
    std::puts("input service host test passed");
    return 0;
}
