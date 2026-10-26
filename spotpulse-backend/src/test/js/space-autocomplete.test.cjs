// Dependency-free behavior tests: node --test src/test/js/space-autocomplete.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const init = require('../../main/resources/static/js/space-autocomplete.js');

class Element {
    constructor(tag = 'div') {
        this.tag = tag;
        this.children = [];
        this.attributes = {};
        this.events = {};
        this.hidden = false;
        this.value = '';
        this.textContent = '';
    }
    setAttribute(name, value) { this.attributes[name] = value; }
    removeAttribute(name) { delete this.attributes[name]; }
    addEventListener(name, fn) { this.events[name] = fn; }
    appendChild(child) { this.children.push(child); }
    append(...children) { this.children.push(...children); }
    replaceChildren() { this.children = []; }
    contains(target) { return target === this || this.children.some(child => child.contains(target)); }
    scrollIntoView() { this.scrolled = true; }
    fire(name, details = {}) {
        const event = { target: this, preventDefault() { this.prevented = true; }, ...details };
        this.events[name]?.(event);
        return event;
    }
}

function setup() {
    const ids = Object.fromEntries(['space-search', 'space-search-input', 'space-search-panel',
        'space-search-results', 'space-search-status'].map(id => [id, new Element()]));
    const root = ids['space-search'], input = ids['space-search-input'];
    const panel = ids['space-search-panel'], list = ids['space-search-results'];
    const status = ids['space-search-status'];
    root.dataset = { url: '/api/spaces/autocomplete', detailUrl: '/spaces/' };
    root.append(input, panel);
    panel.append(list, status);
    panel.hidden = true;
    const document = new Element();
    document.getElementById = id => ids[id];
    document.createElement = tag => new Element(tag);
    document.createTextNode = text => { const node = new Element('#text'); node.textContent = text; return node; };
    let now = 0, timerId = 0;
    const timers = new Map(), requests = [], navigations = [];
    const window = {
        setTimeout(fn, delay) { timers.set(++timerId, { fn, at: now + delay }); return timerId; },
        clearTimeout(id) { timers.delete(id); },
        location: { assign(url) { navigations.push(url); } }
    };
    const $ = { ajax(options) {
        const request = {
            options, aborted: false,
            done(fn) { this.success = fn; return this; },
            fail(fn) { this.failure = fn; return this; },
            abort() { this.aborted = true; this.failure?.(null, 'abort'); }
        };
        requests.push(request);
        return request;
    } };
    init($, document, window);
    return { input, panel, list, status, document, requests, navigations,
        type(value) { input.value = value; input.fire('input'); },
        tick(ms) {
            now += ms;
            for (const [id, timer] of timers) if (timer.at <= now) { timers.delete(id); timer.fn(); }
        }
    };
}
const items = [
    { id: 42, name: 'Creative Hub', city: 'Kyiv', address: 'Street 1' },
    { id: 43, name: 'Quiet Hub', city: 'Lviv', address: 'Street 2' }
];

test('requires three meaningful characters and debounces at 300ms', () => {
    const s = setup();
    for (const value of ['', 'hu', ' h u ', '___', '%%%']) { s.type(value); s.tick(400); }
    assert.equal(s.requests.length, 0);
    s.type('hub'); s.tick(299);
    assert.equal(s.requests.length, 0);
    s.type('  Hub  '); s.tick(299);
    assert.equal(s.requests.length, 0);
    s.tick(1);
    assert.equal(s.requests.length, 1);
    assert.deepEqual(s.requests[0].options.data, { q: 'Hub' });
    assert.equal(s.requests[0].options.url, '/api/spaces/autocomplete');
    assert.equal(s.requests[0].options.dataType, 'json');
});

test('cancels old requests and ignores late successes and failures', () => {
    const s = setup();
    s.type('hub'); s.tick(300);
    const old = s.requests[0];
    s.type('kyiv');
    assert.equal(old.aborted, true);
    old.success(items);
    assert.equal(s.panel.hidden, true);
    s.tick(300); s.requests[1].success([items[1]]);
    old.success(items); old.failure(null, 'error');
    assert.equal(s.list.children.length, 1);
    assert.equal(s.list.children[0].children[0].children.at(-1).textContent, 'Quiet Hub');
});

test('keyboard selection updates ARIA, wraps, and navigates with Enter', () => {
    const s = setup();
    s.type('hub'); s.tick(300); s.requests[0].success(items);
    assert.equal(s.input.attributes['aria-expanded'], 'true');
    assert.equal(s.input.fire('keydown', { key: 'ArrowDown' }).prevented, true);
    assert.equal(s.input.attributes['aria-activedescendant'], 'space-search-option-0');
    assert.equal(s.list.children[0].attributes['aria-selected'], 'true');
    assert.equal(s.list.children[0].scrolled, true);
    s.input.fire('keydown', { key: 'ArrowUp' });
    assert.equal(s.list.children[1].attributes['aria-selected'], 'true');
    s.input.fire('keydown', { key: 'Enter' });
    assert.deepEqual(s.navigations, ['/spaces/43']);
    s.list.children[0].fire('click');
    assert.deepEqual(s.navigations, ['/spaces/43', '/spaces/42']);
});

test('Escape cancels pending and inflight work and closes suggestions', () => {
    const s = setup();
    s.type('hub'); s.input.fire('keydown', { key: 'Escape' }); s.tick(300);
    assert.equal(s.requests.length, 0);
    s.type('hub'); s.tick(300);
    s.input.fire('keydown', { key: 'Escape' }); s.requests[0].success(items);
    assert.equal(s.panel.hidden, true);
    assert.equal(s.input.attributes['aria-expanded'], 'false');
    assert.equal(s.input.attributes['aria-activedescendant'], undefined);
});

test('clearing, Tab, blur and outside click prevent late reopening', () => {
    for (const dismiss of [s => s.type(''), s => s.input.fire('keydown', { key: 'Tab' }),
        s => s.input.fire('blur'), s => s.document.fire('click', { target: new Element() })]) {
        const s = setup();
        s.type('hub'); s.tick(300); s.requests[0].success(items);
        dismiss(s); s.requests[0].success(items);
        assert.equal(s.panel.hidden, true);
    }
});

test('shows empty and network error states and can recover', () => {
    const s = setup();
    s.type('hub'); s.tick(300); s.requests[0].success([]);
    assert.equal(s.panel.hidden, false);
    assert.match(s.status.textContent, /Нічого не знайдено/);
    s.type('kyiv'); s.tick(300); s.requests[1].failure(null, 'timeout');
    assert.match(s.status.textContent, /Не вдалося/);
    s.type('lviv'); s.tick(300); s.requests[2].success(items);
    assert.equal(s.list.children.length, 2);
});

test('highlights with text nodes and never creates user supplied HTML elements', () => {
    const s = setup();
    const value = '<img src=x onerror=alert(1)> Hub';
    s.type('hub'); s.tick(300);
    s.requests[0].success([{ ...items[0], name: value, city: '<script>x</script>', address: value }]);
    const option = s.list.children[0];
    assert.equal(option.children[0].children[0].textContent, '<img src=x onerror=alert(1)> ');
    assert.equal(option.children[0].children[1].tag, 'mark');
    assert.equal(option.children[0].children[1].textContent, 'Hub');
    assert.equal(option.children[2].textContent, value);
    function check(node) {
        assert.notEqual(node.tag, 'img'); assert.notEqual(node.tag, 'script');
        assert.equal(node.innerHTML, undefined);
        node.children.forEach(check);
    }
    check(option);
});

test('composition waits until committed, bounds query length and caps rendered results', () => {
    const s = setup();
    s.input.fire('compositionstart'); s.type('Киї'); s.tick(500);
    assert.equal(s.requests.length, 0);
    s.input.fire('compositionend'); s.tick(300);
    assert.equal(s.requests.length, 1);
    s.requests[0].success(Array.from({ length: 20 }, () => items[0]));
    assert.equal(s.list.children.length, 8);
    s.type('x'.repeat(101)); s.tick(500);
    s.type('hu\nb'); s.tick(500);
    assert.equal(s.requests.length, 1);
});
