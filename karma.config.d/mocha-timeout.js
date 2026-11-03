// Mocha's 2 s default times out heavy browser test suites; match the 120 s Node test timeout.
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 120000 });
