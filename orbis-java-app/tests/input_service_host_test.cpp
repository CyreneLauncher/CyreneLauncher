// Drives the real orbis_input_service.cpp against scripted keyboard batches (input_service_host_fakes.c).
#include "orbis_input_service.h"

#include <cstdio>
#include <cstdlib>
#include <string>
#include <unistd.h>

extern "C" void fake_push_batch(int records, const uint64_t *stamps, const uint16_t keys[][4]);

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
    orbis_input_stop();
    if (failures) return 1;
    std::puts("input service host test passed");
    return 0;
}
