// Build-time shim: Vite runs `net use` via child_process.exec to detect Windows
// mapped network drives. Under the agent sandbox, spawning with piped stdio is
// denied (EPERM). The workspace is on a local drive, so returning "no mapped
// drives" is equivalent to the normal `net use` result and lets the build run.
const cp = require('node:child_process');
const originalExec = cp.exec;

cp.exec = function patchedExec(command, options, callback) {
  if (typeof options === 'function') {
    callback = options;
    options = {};
  }
  if (String(command).trim().toLowerCase() === 'net use') {
    if (callback) {
      process.nextTick(() => callback(null, '', ''));
    }
    return { on() {}, kill() {}, stdout: { on() {} }, stderr: { on() {} } };
  }
  return originalExec.apply(this, arguments);
};
