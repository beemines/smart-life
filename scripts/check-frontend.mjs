import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Script } from 'node:vm';

const root = fileURLToPath(new URL('../frontend/', import.meta.url));
let scripts = 0;
let references = 0;

function checkReference(file, value) {
  if (!value || /^(https?:|data:|javascript:|#)/i.test(value) || value.includes('{{')) return;
  const path = value.split(/[?#]/)[0];
  if (!path) return;
  const target = path.startsWith('/') ? resolve(root, '.' + path) : resolve(dirname(file), path);
  if (!existsSync(target)) throw new Error(`Missing resource: ${file} -> ${value}`);
  references++;
}

function walk(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const file = resolve(directory, entry.name);
    if (entry.isDirectory()) { walk(file); continue; }
    if (!/\.(html|css|js)$/.test(entry.name)) continue;
    const content = readFileSync(file, 'utf8');
    if (entry.name.endsWith('.js')) {
      new Script(content, { filename: file });
      scripts++;
    } else if (entry.name.endsWith('.html')) {
      for (const match of content.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)) {
        if (match[1].trim()) { new Script(match[1], { filename: file }); scripts++; }
      }
      for (const match of content.matchAll(/\b(?:src|href)\s*=\s*["']([^"']+)["']/gi)) {
        if (content[match.index - 1] !== ':') checkReference(file, match[1]);
      }
    } else {
      for (const match of content.matchAll(/url\(\s*["']?([^\s)'";]+)["']?\s*\)/gi)) checkReference(file, match[1]);
    }
  }
}

if (!statSync(root).isDirectory()) throw new Error('Frontend directory is missing');
walk(root);
console.log(`Frontend check passed: ${scripts} scripts, ${references} local references.`);
