/* SPDX-License-Identifier: LGPL-2.1-or-later (part of an OpenAL Soft build)
 *
 * al::semaphore for Orbis, spliced into common/threads.cpp by build_openal.py: a counting
 * semaphore over libkernel's pthread mutex and condition variable, which OpenAL Soft's other
 * locking already relies on, instead of the SDK libc's ksem-backed sem_*.
 */
namespace al {

semaphore::semaphore(unsigned int initial)
{
    if(pthread_mutex_init(&mSem.lock, nullptr) != 0)
        throw std::system_error(std::make_error_code(std::errc::resource_unavailable_try_again));
    if(pthread_cond_init(&mSem.cond, nullptr) != 0)
    {
        pthread_mutex_destroy(&mSem.lock);
        throw std::system_error(std::make_error_code(std::errc::resource_unavailable_try_again));
    }
    mSem.count = initial;
}

semaphore::~semaphore()
{
    pthread_cond_destroy(&mSem.cond);
    pthread_mutex_destroy(&mSem.lock);
}

void semaphore::post()
{
    pthread_mutex_lock(&mSem.lock);
    ++mSem.count;
    pthread_cond_signal(&mSem.cond);
    pthread_mutex_unlock(&mSem.lock);
}

void semaphore::wait() noexcept
{
    pthread_mutex_lock(&mSem.lock);
    while(mSem.count == 0)
        pthread_cond_wait(&mSem.cond, &mSem.lock);
    --mSem.count;
    pthread_mutex_unlock(&mSem.lock);
}

bool semaphore::try_wait() noexcept
{
    pthread_mutex_lock(&mSem.lock);
    const bool taken{mSem.count > 0};
    if(taken) --mSem.count;
    pthread_mutex_unlock(&mSem.lock);
    return taken;
}

} // namespace al
