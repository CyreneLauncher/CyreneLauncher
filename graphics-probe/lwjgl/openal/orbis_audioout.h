#ifndef BACKENDS_ORBIS_AUDIOOUT_H
#define BACKENDS_ORBIS_AUDIOOUT_H

#include "base.h"

/* OpenAL Soft playback backend for the Orbis (PS4) AudioOut service. */
struct OrbisBackendFactory final : public BackendFactory {
public:
    bool init() override;

    bool querySupport(BackendType type) override;

    std::string probe(BackendType type) override;

    BackendPtr createBackend(DeviceBase *device, BackendType type) override;

    static BackendFactory &getFactory();
};

#endif /* BACKENDS_ORBIS_AUDIOOUT_H */
