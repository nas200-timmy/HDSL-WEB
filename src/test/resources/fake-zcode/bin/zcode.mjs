// Test stub for the experimental ZCode category: speaks the one stdout
// contract the launcher relies on — a `ZCode Web is running` line followed by
// `Local:   http://127.0.0.1:<port>/` — then idles, counting seconds on
// stdout (so the logs endpoint has something to tail) until SIGTERM.
//
// The port comes from the `--port` argument; 0 or absent picks a random one.
// The full argv and the ZCODE_DATA_BASE_DIR the launcher injected are written
// to the file FAKE_ZCODE_ARGS_FILE names, which is how the tests assert the
// --token/--workspace/--port pass-through.
import fs from "node:fs";

const args = process.argv.slice(2);

const portIndex = args.indexOf("--port");
let port = portIndex >= 0 ? parseInt(args[portIndex + 1], 10) : 0;
if (!Number.isInteger(port) || port <= 0) {
  port = 20000 + Math.floor(Math.random() * 20000);
}

console.log("ZCode Web is running");
console.log("Local:   http://127.0.0.1:" + port + "/");

const argsFile = process.env.FAKE_ZCODE_ARGS_FILE;
if (argsFile) {
  fs.writeFileSync(
    argsFile,
    JSON.stringify({
      argv: args,
      dataBaseDir: process.env.ZCODE_DATA_BASE_DIR || null,
    }) + "\n",
  );
}

let tick = 0;
setInterval(() => {
  tick += 1;
  console.log("tick " + tick);
}, 1000);

process.on("SIGTERM", () => process.exit(0));
process.on("SIGINT", () => process.exit(0));
