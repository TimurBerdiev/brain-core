function linkUrl(text) {
  try {
    const url = new URL(text);
    return ['https:', 'http:', 'mailto:'].includes(url.protocol) ? url.href : '';
  } catch {
    return '';
  }
}

export function externalLink(doc, text, href) {
  const a = doc.createElement('a');
  a.textContent = text;
  const safe = linkUrl(href);
  if (safe) {
    a.href = safe;
    a.target = '_blank';
    a.rel = 'noopener noreferrer';
  }
  return a;
}

const inlineSyntax = /(!?)\[([^\]\n]*)\]\(([^\s)]+)(?: "[^"]*")?\)|\*\*([^*\n]+)\*\*|\*([^*\n]+)\*|`([^`\n]+)`|(https?:\/\/[^\s<>)]+)/g;

export function markdown(doc, text, loadImage) {
  const root = doc.createElement('div');
  root.className = 'markdown';

  function inline(node, source) {
    let end = 0;
    for (const match of source.matchAll(inlineSyntax)) {
      node.append(source.slice(end, match.index));
      const [raw, image, label, url, bold, emphasis, code, bare] = match;
      if (image && linkUrl(url).startsWith('https:')) {
        const img = doc.createElement('img');
        img.alt = label;
        img.referrerPolicy = 'no-referrer';
        const fallback = doc.createElement('span');
        fallback.textContent = label ? 'Image unavailable: ' + label : 'Image unavailable';
        img.addEventListener('error', () => img.replaceWith(fallback), { once: true });
        node.append(img);
        loadImage(url, img).catch(() => img.replaceWith(fallback));
      } else if (url && !image) node.append(externalLink(doc, label || url, url));
      else if (bare) node.append(externalLink(doc, bare, bare));
      else if (bold || emphasis || code) {
        const span = doc.createElement(bold ? 'strong' : emphasis ? 'em' : 'code');
        span.textContent = bold ?? emphasis ?? code;
        node.append(span);
      } else node.append(raw);
      end = match.index + raw.length;
    }
    node.append(source.slice(end));
  }

  let block = null;
  let code = null;
  for (const line of text.split(/\r\n|\n|\r/)) {
    if (line.startsWith('~~~') || line.startsWith('\x60\x60\x60')) {
      if (code) code = null;
      else {
        code = doc.createElement('pre');
        root.append(code);
      }
      block = null;
    } else if (code) {
      code.append(line + '\n');
    } else if (!line.trim()) {
      block = null;
    } else {
      const heading = line.match(/^#{1,6}\s+(.+)$/);
      const list = line.match(/^\s*(?:[-*+]|\d+\.)\s+(.+)$/);
      const quote = line.match(/^>\s?(.*)$/);
      const tag = heading ? 'h3' : list ? (/^\s*\d/.test(line) ? 'ol' : 'ul') : quote ? 'blockquote' : 'p';
      if (block?.localName !== tag || heading) {
        block = doc.createElement(tag);
        root.append(block);
      }
      if (list) {
        const item = doc.createElement('li');
        inline(item, list[1]);
        block.append(item);
      } else {
        if (block.hasChildNodes()) block.append(doc.createElement('br'));
        inline(block, heading?.[1] ?? quote?.[1] ?? line);
      }
    }
  }
  return root;
}
