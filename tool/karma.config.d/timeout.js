// Mocha's 2 s default is too short for a browser test that renders Compose and waits for a mock request in real time
// (the devtools UI's replay tests).
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 15000 });
