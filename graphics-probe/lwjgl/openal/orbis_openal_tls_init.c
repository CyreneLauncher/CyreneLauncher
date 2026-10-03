/* SPDX-License-Identifier: BSD-3-Clause
 * The compiler references a thread-local initialisation hook for ALCcontext::sLocalContext
 * as an undefined weak symbol and calls it only when defined. The variable is constant-
 * initialised (nullptr), so the hook is never needed, but create-fself cannot place an
 * undefined symbol in any library. Define it as the no-op it would be.
 */
void _ZTHN10ALCcontext13sLocalContextE(void) {}
