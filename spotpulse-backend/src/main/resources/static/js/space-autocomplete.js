(function (factory) {
    'use strict';
    if (typeof module === 'object' && module.exports) module.exports = factory;
    else if (window.jQuery) window.jQuery(function () { factory(window.jQuery, document, window); });
}(function initSpaceAutocomplete($, document, window) {
    'use strict';
    const root = document.getElementById('space-search');
    if (!root) return;
    const input = document.getElementById('space-search-input');
    const panel = document.getElementById('space-search-panel');
    const list = document.getElementById('space-search-results');
    const status = document.getElementById('space-search-status');
    let timer, request, generation = 0, suggestions = [], active = -1, composing = false;

    function close() {
        panel.hidden = true;
        input.setAttribute('aria-expanded', 'false');
        input.removeAttribute('aria-activedescendant');
        active = -1;
    }

    function cancel() {
        generation++;
        window.clearTimeout(timer);
        if (request) request.abort();
        request = null;
    }

    function message(text) {
        status.textContent = text;
        status.hidden = !text;
        panel.hidden = false;
        input.setAttribute('aria-expanded', 'true');
    }

    // Build text nodes and <mark> elements; server values never become HTML.
    function highlighted(text, query) {
        const span = document.createElement('span');
        const value = String(text || '');
        const lower = value.toLowerCase(), needle = query.toLowerCase();
        let from = 0, match;
        while ((match = lower.indexOf(needle, from)) !== -1) {
            span.appendChild(document.createTextNode(value.slice(from, match)));
            const mark = document.createElement('mark');
            mark.textContent = value.slice(match, match + query.length);
            span.appendChild(mark);
            from = match + query.length;
        }
        span.appendChild(document.createTextNode(value.slice(from)));
        return span;
    }

    function select(index) {
        const item = suggestions[index];
        if (item) window.location.assign(root.dataset.detailUrl + encodeURIComponent(item.id));
    }

    function render(items, query) {
        suggestions = items.slice(0, 8);
        active = -1;
        list.replaceChildren();
        input.removeAttribute('aria-activedescendant');
        suggestions.forEach(function (item, index) {
            const option = document.createElement('li');
            option.id = 'space-search-option-' + index;
            option.className = 'space-search-option p-3 border-bottom';
            option.setAttribute('role', 'option');
            option.setAttribute('aria-selected', 'false');
            const name = highlighted(item.name, query);
            name.className = 'd-block fw-semibold';
            const city = highlighted(item.city, query);
            city.className = 'd-block small text-muted';
            const address = document.createElement('span');
            address.className = 'd-block small text-muted';
            address.textContent = item.address || '';
            option.append(name, city, address);
            // Keep the combobox focused until click selection completes.
            option.addEventListener('mousedown', function (event) { event.preventDefault(); });
            option.addEventListener('click', function () { select(index); });
            list.appendChild(option);
        });
        message(suggestions.length ? suggestions.length + ' результатів. Оберіть простір.' : 'Нічого не знайдено. Спробуйте іншу назву або місто.');
    }

    function search() {
        cancel();
        close();
        list.replaceChildren();
        suggestions = [];
        const query = input.value.trim();
        const meaningful = query.match(/[\p{L}\p{N}]/gu) || [];
        if (composing || query.length > 100 || meaningful.length < 3 || /[\u0000-\u001f\u007f-\u009f]/u.test(query)) return;
        const version = generation;
        timer = window.setTimeout(function () {
            message('Пошук…');
            request = $.ajax({
                url: root.dataset.url,
                data: { q: query },
                dataType: 'json',
                timeout: 8000
            }).done(function (items) {
                if (version !== generation) return;
                if (Array.isArray(items)) render(items, query);
                else message('Не вдалося виконати пошук. Спробуйте ще раз.');
            }).fail(function (_xhr, reason) {
                if (version === generation && reason !== 'abort') {
                    message('Не вдалося виконати пошук. Перевірте з’єднання та спробуйте ще раз.');
                }
            });
        }, 300);
    }

    input.addEventListener('input', search);
    input.addEventListener('compositionstart', function () { composing = true; cancel(); close(); });
    input.addEventListener('compositionend', function () { composing = false; search(); });
    input.addEventListener('keydown', function (event) {
        if (event.isComposing) return;
        if (event.key === 'Escape' || event.key === 'Tab') {
            cancel();
            close();
            return;
        }
        if (panel.hidden || !suggestions.length) return;
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            active = active < 0 ? (event.key === 'ArrowDown' ? 0 : suggestions.length - 1)
                : (active + (event.key === 'ArrowDown' ? 1 : -1) + suggestions.length) % suggestions.length;
            Array.from(list.children).forEach(function (option, index) {
                option.setAttribute('aria-selected', String(index === active));
            });
            input.setAttribute('aria-activedescendant', list.children[active].id);
            list.children[active].scrollIntoView({ block: 'nearest' });
        } else if (event.key === 'Enter' && active >= 0) {
            event.preventDefault();
            select(active);
        }
    });
    input.addEventListener('blur', function () { cancel(); close(); });
    document.addEventListener('click', function (event) {
        if (!root.contains(event.target)) { cancel(); close(); }
    });
}));
