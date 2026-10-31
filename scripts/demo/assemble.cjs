// Joins the clips from record-demo.cjs into docs/demo/ridehail-demo.mp4 (the full demo, with the narration placed where each scene
// said it) and ridehail-demo.gif (a short silent preview for the README). Needs ffmpeg.
//
//   node scripts/demo/assemble.cjs <clips-folder> [audio-folder] [output-folder]
//
// <audio-folder> is what narrate.cjs wrote (one mp3 per line); without it the video is silent.
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const CLIPS = path.resolve(process.argv[2] || 'demo-out');
const AUDIO = process.argv[3] && process.argv[3] !== '-' ? path.resolve(process.argv[3]) : null;
const OUT = path.resolve(process.argv[4] || 'docs/demo');
const FPS = 30;
fs.mkdirSync(OUT, { recursive: true });

// the order of the film; two clips in a scene are shown side by side
const SCENES = [
  { name: '0-title', files: ['0-title.webm'] },
  { name: 'ride', files: ['ride-customer.webm', 'ride-rider.webm'] },
  { name: 'rush', files: ['rush-rider.webm', 'rush-admin.webm'] },
  { name: 'admin', files: ['admin.webm'] },
  { name: '9-end', files: ['9-end.webm'] },
];
const seconds = (f) => Number(execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', f]).toString().trim());
const LEAD = 0.2; // the recordings start a moment before each scene's clock does

const inputs = [];
const filters = [];
const labels = [];
const placed = []; // narration lines: { id, at (seconds into the film) }
let offset = 0;

for (const scene of SCENES) {
  const files = scene.files.map((f) => path.join(CLIPS, f));
  const length = Math.max(...files.map(seconds));
  const label = 'v' + labels.length;
  const idx = files.map((f) => { inputs.push(f); return inputs.length - 1; });
  if (files.length === 1) {
    filters.push(`[${idx[0]}:v]fps=${FPS},scale=1280:720,setsar=1,format=yuv420p[${label}]`);
  } else {
    filters.push(`[${idx[0]}:v]fps=${FPS},scale=640:720,setsar=1[${label}a]`, `[${idx[1]}:v]fps=${FPS},scale=640:720,setsar=1[${label}b]`, `[${label}a][${label}b]hstack=inputs=2,format=yuv420p[${label}]`);
  }
  labels.push(label);
  const timeline = path.join(CLIPS, scene.name + '.timeline.json');
  if (fs.existsSync(timeline)) for (const { id, t } of JSON.parse(fs.readFileSync(timeline, 'utf8'))) placed.push({ id, at: offset + LEAD + t, scene: scene.name });
  scene.start = offset;
  offset += length;
}
const total = offset;

const args = ['-y', '-loglevel', 'error'];
for (const f of inputs) args.push('-i', f);
const voices = [];
if (AUDIO) {
  placed.sort((x, y) => x.at - y.at);
  placed.forEach((p, i) => {
    const file = path.join(AUDIO, p.id + '.mp3');
    if (!fs.existsSync(file)) return;
    const next = placed[i + 1]?.at ?? total;
    const length = seconds(file);
    if (p.at + length > next + 0.05) console.warn(`warning: "${p.id}" runs ${(p.at + length - next).toFixed(1)}s into the next line; re-record with this audio (narrate, then record)`);
    args.push('-i', file);
    voices.push({ input: inputs.length + voices.length, ms: Math.round(p.at * 1000) });
  });
  voices.forEach((v) => filters.push(`[${v.input}:a]adelay=${v.ms}|${v.ms},aresample=44100[a${v.input}]`));
}
filters.push(`${labels.map((l) => `[${l}]`).join('')}concat=n=${labels.length}:v=1:a=0[v]`);
if (voices.length) filters.push(`${voices.map((v) => `[a${v.input}]`).join('')}amix=inputs=${voices.length}:normalize=0:dropout_transition=0,apad=whole_dur=${total.toFixed(2)}[aout]`);

args.push('-filter_complex', filters.join(';'), '-map', '[v]');
if (voices.length) args.push('-map', '[aout]', '-c:a', 'aac', '-b:a', '128k');
args.push('-c:v', 'libx264', '-preset', 'slow', '-crf', '28', '-movflags', '+faststart', '-t', total.toFixed(2), path.join(OUT, 'ridehail-demo.mp4'));
execFileSync('ffmpeg', args, { stdio: 'inherit' });

// a silent preview that plays inline on GitHub: the ride, from the fare to the end of the drive, small and slow
const ride = SCENES.find((s) => s.name === 'ride');
const fare = placed.find((p) => p.id === 'fare');
const from = fare ? fare.at : ride.start + 8;
execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-ss', from.toFixed(2), '-t', '48', '-i', path.join(OUT, 'ridehail-demo.mp4'), '-an',
  '-vf', 'fps=6,scale=720:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=96[p];[b][p]paletteuse=dither=bayer:bayer_scale=4', path.join(OUT, 'ridehail-demo.gif')], { stdio: 'inherit' });

const mb = (f) => (fs.statSync(path.join(OUT, f)).size / 1e6).toFixed(1) + ' MB';
console.log(`ridehail-demo.mp4 ${mb('ridehail-demo.mp4')}, ${total.toFixed(0)}s${voices.length ? ', ' + voices.length + ' narration lines' : ', silent'}; ridehail-demo.gif ${mb('ridehail-demo.gif')}`);
