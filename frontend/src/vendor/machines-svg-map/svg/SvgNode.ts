/**
 * Minimal SVG element tree, serialized to markup without any DOM: the generator runs as is in Node and in browsers.
 */

export type AttributeValue = string | number | undefined;

export interface SvgNode {
  tag: string;
  attrs: Record<string, AttributeValue>;
  children: (SvgNode | string)[];
}

export function el(tag: string, attrs: Record<string, AttributeValue> = {}, ...children: (SvgNode | string | null | undefined)[]): SvgNode {
  return { tag, attrs, children: children.filter((child): child is SvgNode | string => child != null) };
}

const XML_ESCAPES: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' };

function escapeXml(text: string): string {
  return text.replace(/[&<>"]/g, c => XML_ESCAPES[c]);
}

/** Rounds away the floating point noise of computed coordinates (1e-16 instead of 0, ...). */
function formatNumber(value: number): string {
  return String(Math.round(value * 1e6) / 1e6);
}

function formatAttribute(value: string | number): string {
  return typeof value === 'number' ? formatNumber(value) : escapeXml(value);
}

/** Serializes the tree as indented markup. `<style>` content is written as is. */
export function serialize(node: SvgNode, indent = ''): string {
  const attrs = Object.entries(node.attrs)
    .filter((entry): entry is [string, string | number] => entry[1] !== undefined)
    .map(([name, value]) => ` ${name}="${formatAttribute(value)}"`)
    .join('');
  if (node.children.length === 0) {
    return `${indent}<${node.tag}${attrs}/>`;
  }
  if (node.children.every(child => typeof child === 'string')) {
    const text = (node.children as string[]).join('');
    return `${indent}<${node.tag}${attrs}>${node.tag === 'style' ? text : escapeXml(text)}</${node.tag}>`;
  }
  const inner = indent + '  ';
  const children = node.children
    .map(child => typeof child === 'string' ? inner + escapeXml(child) : serialize(child, inner))
    .join('\n');
  return `${indent}<${node.tag}${attrs}>\n${children}\n${indent}</${node.tag}>`;
}

/** First node of the tree with this id. */
export function findById(root: SvgNode, id: string): SvgNode | undefined {
  if (root.attrs['id'] === id) {
    return root;
  }
  for (const child of root.children) {
    if (typeof child !== 'string') {
      const found = findById(child, id);
      if (found) {
        return found;
      }
    }
  }
  return undefined;
}

// ------------------------------------------------------------------ transforms

/** Affine transform as an SVG matrix (a, b, c, d, e, f). */
export type Matrix = [number, number, number, number, number, number];

export const IDENTITY: Matrix = [1, 0, 0, 1, 0, 0];

export function multiply(m: Matrix, n: Matrix): Matrix {
  return [
    m[0] * n[0] + m[2] * n[1], m[1] * n[0] + m[3] * n[1],
    m[0] * n[2] + m[2] * n[3], m[1] * n[2] + m[3] * n[3],
    m[0] * n[4] + m[2] * n[5] + m[4], m[1] * n[4] + m[3] * n[5] + m[5]];
}

export function applyMatrix(m: Matrix, x: number, y: number): [number, number] {
  return [m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]];
}

/** Applies only the linear part of the matrix (rotation / scale), e.g. to a direction. */
export function applyLinear(m: Matrix, x: number, y: number): [number, number] {
  return [m[0] * x + m[2] * y, m[1] * x + m[3] * y];
}

export function invert(m: Matrix): Matrix {
  const det = m[0] * m[3] - m[1] * m[2];
  return [
    m[3] / det, -m[1] / det, -m[2] / det, m[0] / det,
    (m[2] * m[5] - m[3] * m[4]) / det, (m[1] * m[4] - m[0] * m[5]) / det];
}

const TRANSFORM = /(\w+)\s*\(([^)]*)\)/g;

/** Parses the `matrix`, `translate`, `rotate` and `scale` functions of an SVG transform attribute. */
export function parseTransform(transform: string | undefined): Matrix {
  let matrix = IDENTITY;
  for (const [, fn, args] of (transform ?? '').matchAll(TRANSFORM)) {
    const v = args.trim().split(/[\s,]+/).map(Number);
    let next: Matrix;
    switch (fn) {
      case 'matrix':
        next = [v[0], v[1], v[2], v[3], v[4], v[5]];
        break;
      case 'translate':
        next = [1, 0, 0, 1, v[0], v[1] ?? 0];
        break;
      case 'scale':
        next = [v[0], 0, 0, v[1] ?? v[0], 0, 0];
        break;
      case 'rotate': {
        const r = v[0] * Math.PI / 180;
        const rotation: Matrix = [Math.cos(r), Math.sin(r), -Math.sin(r), Math.cos(r), 0, 0];
        next = v.length === 3
          ? multiply(multiply([1, 0, 0, 1, v[1], v[2]], rotation), [1, 0, 0, 1, -v[1], -v[2]])
          : rotation;
        break;
      }
      default:
        throw new Error(`Unsupported transform function '${fn}' in '${transform}'`);
    }
    matrix = multiply(matrix, next);
  }
  return matrix;
}

/** Visits every node with the transform from its own coordinate system to the root's (its `transform` included). */
export function walk(root: SvgNode, visit: (node: SvgNode, toRoot: Matrix) => void, parentToRoot: Matrix = IDENTITY): void {
  const toRoot = multiply(parentToRoot, parseTransform(root.attrs['transform'] as string | undefined));
  visit(root, toRoot);
  for (const child of root.children) {
    if (typeof child !== 'string') {
      walk(child, visit, toRoot);
    }
  }
}

/** Center of a `rect`, `circle` or `polygon` in its own coordinate system, undefined for other elements. */
export function localCenter(node: SvgNode): [number, number] | undefined {
  const num = (name: string) => Number(node.attrs[name] ?? 0);
  switch (node.tag) {
    case 'rect':
      return [num('x') + num('width') / 2, num('y') + num('height') / 2];
    case 'circle':
      return [num('cx'), num('cy')];
    case 'polygon': {
      const values = String(node.attrs['points'] ?? '').trim().split(/[\s,]+/).map(Number);
      const xs = values.filter((_, i) => i % 2 === 0);
      const ys = values.filter((_, i) => i % 2 === 1);
      return [(Math.min(...xs) + Math.max(...xs)) / 2, (Math.min(...ys) + Math.max(...ys)) / 2];
    }
    default:
      return undefined;
  }
}
