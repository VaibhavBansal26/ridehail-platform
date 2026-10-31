// Turns narration.json into one audio file per line, and timing.json (the length of each line in seconds), which record-demo.cjs
// uses to wait for each sentence to finish before moving on.
//
//   ELEVENLABS_API_KEY=... [ELEVENLABS_VOICE_ID=...] node scripts/demo/narrate.cjs <output-folder>
//   node scripts/demo/narrate.cjs <output-folder> --system        # a macOS system voice instead (a stand-in; no key needed)
//
// The key is read from the environment only. It is never written anywhere or printed.
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const OUT = path.resolve(process.argv[2] || 'demo-audio');
const useSystem = process.argv.includes('--system') || !process.env.ELEVENLABS_API_KEY;
const { elevenlabs, lines } = require('./narration.json');
const VOICE = process.env.ELEVENLABS_VOICE_ID || '21m00Tcm4TlvDq8ikWAM'; // "Rachel", one of ElevenLabs' stock voices: set ELEVENLABS_VOICE_ID to use yours

const seconds = (file) => Number(execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', file]).toString().trim());

async function eleven(text, file) {
  const res = await fetch(`https://api.elevenlabs.io/v1/text-to-speech/${VOICE}?output_format=mp3_44100_128`, {
    method: 'POST',
    headers: { 'xi-api-key': process.env.ELEVENLABS_API_KEY, 'Content-Type': 'application/json', Accept: 'audio/mpeg' },
    body: JSON.stringify({ text, model_id: elevenlabs.model, voice_settings: { stability: elevenlabs.stability, similarity_boost: elevenlabs.similarity_boost } }),
  });
  if (!res.ok) throw new Error(`ElevenLabs answered ${res.status}: ${(await res.text()).slice(0, 200)}`); // the key is never in this message
  fs.writeFileSync(file, Buffer.from(await res.arrayBuffer()));
}

function system(text, file) {
  const aiff = file.replace(/\.mp3$/, '.aiff');
  const installed = execFileSync('say', ['-v', '?']).toString();
  const voice = process.env.SYSTEM_VOICE || ['Samantha', 'Ava', 'Daniel', 'Karen'].find((v) => new RegExp('^' + v + '\\s', 'm').test(installed)) || 'Daniel';
  execFileSync('say', ['-v', voice, '-r', '170', '-o', aiff, text]);
  execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', aiff, '-codec:a', 'libmp3lame', '-qscale:a', '3', file]);
  fs.unlinkSync(aiff);
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  console.log(useSystem ? 'Using a macOS system voice (a stand-in).' : `Using ElevenLabs, voice ${VOICE}.`);
  const timing = {};
  for (const { id, text } of lines) {
    const file = path.join(OUT, id + '.mp3');
    await (useSystem ? system(text, file) : eleven(text, file));
    timing[id] = Number(seconds(file).toFixed(2));
    console.log(id.padEnd(10), timing[id].toFixed(1) + 's');
  }
  fs.writeFileSync(path.join(OUT, 'timing.json'), JSON.stringify({ voice: useSystem ? 'system' : 'elevenlabs', ...timing }, null, 2));
})().catch((e) => { console.error(e.message); process.exit(1); });
