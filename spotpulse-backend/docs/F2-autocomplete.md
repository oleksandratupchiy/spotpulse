# F2: integrated space autocomplete

Open `/spaces` and search by space name or city. The existing cards and management links remain available.

`GET /api/spaces/autocomplete?q=Hub` returns a JSON array of at most eight objects with exactly `id`, `name`, `city`, and `address`.

- Missing queries and queries with fewer than three Unicode letters/digits return `[]` without database work. Whitespace and punctuation do not count toward the threshold.
- Surrounding whitespace is stripped. The raw input is limited to 100 UTF-16 code units; longer input or embedded control characters return HTTP 400 using the existing problem-detail handler.
- Matching is case insensitive and searches name/city substrings. SQL LIKE wildcards (`%`, `_`, `!`) are escaped and treated literally.
- Results are ordered by lowercase name, lowercase city, then unique ID. The database applies the eight-row limit and projects only the four response fields, without loading space entities or their relationships.
- Existing `/api/spaces` page/size and B1 skip/limit/nextLink contracts are unchanged.

The listing uses jQuery 3.7.1 AJAX, a 300 ms debounce, request abortion plus a generation check, and an eight-second timeout. Suggestions use text nodes and mark elements for safe highlighting. Arrow keys select results; Enter opens the selected detail page; Escape, Tab, clearing the input, losing focus, and clicking outside close suggestions. Input composition waits until committed. Empty and error states are displayed in Ukrainian with the rest of the interface.

From `spotpulse-backend` in PowerShell:

```powershell
.\mvnw.cmd -B -DskipTests compile
.\mvnw.cmd -B -Dtest=SpaceAutocompleteTests test
.\mvnw.cmd -B test
node --test src/test/js/space-autocomplete.test.cjs
```

Manual demonstration: create spaces in different cities, type two then three characters, try mixed case, select a result using both mouse and keyboard, try an unmatched query, and clear the field. Verify the detail, create/edit/delete links still work. Browser interaction and assistive-technology checks require a real browser; the lightweight Node tests use a minimal DOM/AJAX substitute.

jQuery and Bootstrap load from CDNs, so the demonstration browser needs internet access. Existing space cards remain available if JavaScript cannot load. Database substring matching may scan many rows on a large catalog; the response and loaded data remain bounded. No frontend framework or deployment changes are required.

Verification on 2026-10-26 with Java 25 / Spring Boot 4.1.1:

- Backend compile: Maven `BUILD SUCCESS`.
- Focused integration tests: 27 passed, zero failures/errors/skips.
- Complete suite (`.\mvnw.cmd -B package`): 85 passed, zero failures/errors/skips; Maven `BUILD SUCCESS`, executable JAR created.
- Node behavior tests: 8 passed, zero failures/skips.
- Live HTTP smoke test of the packaged JAR on port 18080 with an isolated H2 database: passed for mixed-case/trimmed name search, city search, short/unmatched/wildcard input, overlength HTTP 400, exact JSON fields, B1 nextLink traversal, legacy pagination, listing markup, AJAX asset, and detail page. The temporary process was stopped afterward.
- Browser interaction and assistive-technology testing were not performed.
