/* SPDX-License-Identifier: LGPL-2.1-or-later (part of an OpenAL Soft build)
 *
 * OpenAL Soft playback backend for the Orbis (PS4) AudioOut service.
 *
 * AudioOut takes fixed periods of 256 frames on the main port and sceAudioOutOutput blocks
 * until the hardware wants the next one, so a mixer thread that renders a period and hands it
 * over is paced by the device itself - no timers. The device format is pinned to what the port
 * accepts (48 kHz float stereo); OpenAL Soft resamples and downmixes everything else.
 */
#include "config.h"

#include "orbis_audioout.h"

#include <atomic>
#include <cstdint>
#include <cstring>
#include <exception>
#include <functional>
#include <thread>
#include <vector>

#include <orbis/AudioOut.h>
#include <orbis/libkernel.h>

#include "almalloc.h"
#include "core/device.h"
#include "core/logging.h"
#include "threads.h"

namespace {

constexpr char orbisDevice[] = "Orbis AudioOut";
constexpr uint32_t kFrequency = 48000;
constexpr uint32_t kPeriodFrames = 256;
/* The system user owns the main port; no per-user login is involved. */
constexpr int32_t kSystemUser = 0xFF;
/* sceAudioOutInit reports this when the service is already initialised in the process. */
constexpr int32_t kAlreadyInitialised = static_cast<int32_t>(0x8026000E);
/* Well above the game's default 700: the mixer must hand AudioOut each 5.3 ms period on time
 * while the render and loader threads are busy, or the output underruns and crackles. It
 * spends most of each period blocked in sceAudioOutOutput, so it cannot hog a core. */
constexpr int kMixerPriority = 0x120;

struct OrbisBackend final : public BackendBase {
    OrbisBackend(DeviceBase *device) noexcept : BackendBase{device} { }
    ~OrbisBackend() override;

    int mixerProc();

    void open(const char *name) override;
    bool reset() override;
    void start() override;
    void stop() override;

    int32_t mPort{-1};
    std::vector<float> mBuffer;
    std::atomic<bool> mKillNow{true};
    std::thread mThread;

    DEF_NEWDEL(OrbisBackend)
};

OrbisBackend::~OrbisBackend()
{
    if(mPort >= 0)
        sceAudioOutClose(mPort);
    mPort = -1;
}

int OrbisBackend::mixerProc()
{
    const int32_t prio{scePthreadSetprio(scePthreadSelf(), kMixerPriority)};
    if(prio < 0)
        WARN("scePthreadSetprio(%d) failed: 0x%08x\n", kMixerPriority, static_cast<uint32_t>(prio));
    althrd_setname(MIXER_THREAD_NAME);

    while(!mKillNow.load(std::memory_order_acquire)
        && mDevice->Connected.load(std::memory_order_acquire))
    {
        mDevice->renderSamples(mBuffer.data(), kPeriodFrames, 2u);
        const int32_t result{sceAudioOutOutput(mPort, mBuffer.data())};
        if(result < 0)
        {
            mDevice->handleDisconnect("sceAudioOutOutput failed: 0x%08x",
                static_cast<uint32_t>(result));
            break;
        }
    }
    return 0;
}

void OrbisBackend::open(const char *name)
{
    if(!name)
        name = orbisDevice;
    else if(strcmp(name, orbisDevice) != 0)
        throw al::backend_exception{al::backend_error::NoDevice, "Device name \"%s\" not found",
            name};

    /* OpenAL Soft reports a failed open only at warning level; these always reach the log. */
    const int32_t init{sceAudioOutInit()};
    if(init < 0 && init != kAlreadyInitialised)
        ERR("sceAudioOutInit failed: 0x%08x\n", static_cast<uint32_t>(init));
    if(init < 0 && init != kAlreadyInitialised)
        throw al::backend_exception{al::backend_error::DeviceError,
            "sceAudioOutInit failed: 0x%08x", static_cast<uint32_t>(init)};

    const int32_t port{sceAudioOutOpen(kSystemUser, ORBIS_AUDIO_OUT_PORT_TYPE_MAIN, 0,
        kPeriodFrames, kFrequency, ORBIS_AUDIO_OUT_PARAM_FORMAT_FLOAT_STEREO)};
    if(port < 0)
        ERR("sceAudioOutOpen failed: 0x%08x\n", static_cast<uint32_t>(port));
    if(port < 0)
        throw al::backend_exception{al::backend_error::DeviceError,
            "sceAudioOutOpen failed: 0x%08x", static_cast<uint32_t>(port)};

    if(mPort >= 0)
        sceAudioOutClose(mPort);
    mPort = port;
    mDevice->DeviceName = name;
    TRACE("Opened %s port %d: %u Hz float stereo, %u-frame periods\n", name, port, kFrequency,
        kPeriodFrames);
}

bool OrbisBackend::reset()
{
    mDevice->Frequency = kFrequency;
    mDevice->FmtChans = DevFmtStereo;
    mDevice->FmtType = DevFmtFloat;
    mDevice->UpdateSize = kPeriodFrames;
    mDevice->BufferSize = kPeriodFrames * 2;
    setDefaultWFXChannelOrder();
    mBuffer.assign(size_t{kPeriodFrames} * 2u, 0.0f);
    return true;
}

void OrbisBackend::start()
{
    try {
        mKillNow.store(false, std::memory_order_release);
        mThread = std::thread{std::mem_fn(&OrbisBackend::mixerProc), this};
    }
    catch(std::exception& e) {
        throw al::backend_exception{al::backend_error::DeviceError,
            "Failed to start mixing thread: %s", e.what()};
    }
}

void OrbisBackend::stop()
{
    if(mKillNow.exchange(true, std::memory_order_acq_rel) || !mThread.joinable())
        return;
    try {
        mThread.join();
    }
    catch(std::exception& e) {
        /* Leaving it joinable would std::terminate when the backend is destroyed. */
        ERR("Failed to join mixing thread: %s\n", e.what());
        mThread.detach();
    }
}

} // namespace


bool OrbisBackendFactory::init()
{ return true; }

bool OrbisBackendFactory::querySupport(BackendType type)
{ return (type == BackendType::Playback); }

std::string OrbisBackendFactory::probe(BackendType type)
{
    std::string outnames;
    switch(type)
    {
    case BackendType::Playback:
        /* Includes null char. */
        outnames.append(orbisDevice, sizeof(orbisDevice));
        break;
    case BackendType::Capture:
        break;
    }
    return outnames;
}

BackendPtr OrbisBackendFactory::createBackend(DeviceBase *device, BackendType type)
{
    if(type == BackendType::Playback)
        return BackendPtr{new OrbisBackend{device}};
    return nullptr;
}

BackendFactory &OrbisBackendFactory::getFactory()
{
    static OrbisBackendFactory factory{};
    return factory;
}
