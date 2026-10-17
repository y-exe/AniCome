const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync('app/src/main/assets/comment-preview.html', 'utf8');
const source = html.match(/<script>([\s\S]*?)<\/script>/)[1];
let configuration;
let position = 0, duration = 90, state = 2;
let timeout;
const context = vm.createContext({
  setTimeout: callback => timeout = callback,
  YT: {Player: function (element, options) {
    configuration = options;
    return {getCurrentTime: () => position, getDuration: () => duration, getPlayerState: () => state};
  }}
});
vm.runInContext(source, context);
const read = () => JSON.parse(vm.runInContext('previewStatus()', context));
assert.equal(read().playing, false);
vm.runInContext('onYouTubeIframeAPIReady()', context);
assert.equal(configuration.videoId, '4uyUPa4V_8o');
assert.equal(configuration.playerVars.origin, 'https://yexe.net');
configuration.events.onReady();
state = 1; position = 12.5;
assert.deepEqual(read(), {position: 12.5, duration: 90, playing: true, error: 0, speed: 1});
position = 5;
assert.equal(read().position, 5);
state = 2;
assert.equal(read().playing, false);
state = 3;
assert.equal(read().playing, false);
timeout();
assert.equal(read().error, 0);
configuration.events.onError({data: 153});
assert.equal(read().error, 153);
vm.runInContext('playerReady=false;playerError=0', context);
timeout();
assert.equal(read().error, -1);
console.log('Preview player: video ID, origin, position, seek, pause, buffering and errors passed.');
