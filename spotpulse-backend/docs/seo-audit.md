# Space landing pages: deployment and SEO audit

The landing pages are part of the existing Thymeleaf application at `/spaces/{id}`.
No external SEO audit has been performed and no score is claimed.

## Configuration

Set `SPOTPULSE_PUBLIC_BASE_URL` to the real public HTTP(S) origin, including the
application context path if one is used, for example `https://your-domain.example`.
Do not include query parameters or a fragment. Canonical and Open Graph URLs,
JSON-LD URLs, and sitemaps use this value, never the incoming Host header.

Set `SPOTPULSE_SEO_INDEXABLE=true` on the public site. Set it to `false` on staging.
Without a public base URL, detail pages use `noindex, follow`, robots.txt blocks
crawling, and sitemap endpoints return 404. With indexing enabled, `/sitemap.xml`
is an index of `/sitemaps/spaces-0.xml`, etc. Each child fetches at most 1,000 IDs
from the database; no room collections are loaded for sitemap generation.
For deployments under a context path, the host/proxy must also expose robots.txt
at the origin root (`/robots.txt`), where crawlers look for it; the application
emits rules that include the configured context path.

## Content preparation

Use the existing create/edit forms to supply real names, descriptions, addresses,
image URLs, and optional public contact emails. Emails are validated and persisted
in the nullable `spaces.contact_email` column. Existing records are not populated
with sample contacts. The existing Hibernate `ddl-auto=update` setting adds the
column; environments using externally managed schemas must add the nullable
`varchar(254)` column before enabling this version.

Room rates are shown as stored. The current model has no currency or time-specific
availability field; pages do not claim a currency or guaranteed room availability.
Schema.org Place/PostalAddress JSON-LD contains actual name, address, description,
and optional image/URL. It makes no claims about ratings, amenities, or offers.

## Local checks

Run `SpaceLandingTests` and `SpaceSeoTests`: these verify rendered HTML, titles,
descriptions, canonical/OG metadata, JSON-LD escaping, robots, sitemap XML,
navigation, contact validation/persistence, empty states, and bounded room-query
counts. Existing regression and pagination tests must also pass.

Start the application and request `/spaces`, `/spaces/{existing-id}`, a missing ID,
`/robots.txt`, and the sitemap endpoints. Inspect rendered source for metadata.
Visual desktop/mobile checks should verify wrapping, the sticky desktop panel,
fixed mobile action, keyboard focus, image failure fallback, and Bootstrap CDN
availability. Automated HTML checks do not constitute visual browser testing.

## External audit after deployment

1. Verify that the real public URL is reachable without login and returns 200.
   Confirm HTTPS, correct canonical origin, `index, follow`, robots access, image
   access, and the sitemap index and child sitemap URLs.
2. Open the free Seobility SEO Checker at https://www.seobility.net/en/seocheck/
   and submit the full URL of a populated `/spaces/{id}` page. Audit another space
   to confirm unique metadata. A local/private address cannot be audited externally.
3. Save the actual date, audited URL, observed score, findings, and screenshots in
   the university submission. Do not report a score until the service returns one.
4. Fix relevant findings and rerun the audit. Separately check structured data at
   https://validator.schema.org/; generic Place markup does not promise a Google
   rich result. Public accessibility, real crawler behavior, indexing, live image
   and CDN reachability, and deployed performance require deployment checks.

C7's required third-party evaluation remains pending until a public URL is audited.
