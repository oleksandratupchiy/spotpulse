package com.spotpulse.controller;

import com.spotpulse.seo.SpaceSeo;
import com.spotpulse.service.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class SeoController {
    private final SpaceSeo seo;
    private final SpaceService spaces;
    private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    @GetMapping(value = "/robots.txt", produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public String robots() {
        if (!seo.isIndexable()) return "User-agent: *\nDisallow: /\n";
        return "User-agent: *\nAllow: /\nDisallow: " + seo.publicPath("/api/")
                + "\nDisallow: " + seo.publicPath("/spaces/new")
                + "\nDisallow: " + seo.publicPath("/spaces/*/edit")
                + "\nDisallow: " + seo.publicPath("/h2-console") + "\n"
                + "Sitemap: " + seo.publicUrl("/sitemap.xml") + "\n";
    }

    @GetMapping(value = "/sitemap.xml", produces = "application/xml;charset=UTF-8")
    @ResponseBody
    public String sitemapIndex() {
        requirePublicSite();
        long pages = Math.max(1, (spaces.countSpaces() + SpaceService.SITEMAP_PAGE_SIZE - 1) / SpaceService.SITEMAP_PAGE_SIZE);
        StringBuilder xml = new StringBuilder(XML_HEADER).append("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        for (long page = 0; page < pages; page++) {
            xml.append("<sitemap><loc>").append(escape(seo.publicUrl("/sitemaps/spaces-" + page + ".xml"))).append("</loc></sitemap>");
        }
        return xml.append("</sitemapindex>").toString();
    }

    @GetMapping(value = "/sitemaps/spaces-{page}.xml", produces = "application/xml;charset=UTF-8")
    @ResponseBody
    public String spacesSitemap(@PathVariable int page) {
        requirePublicSite();
        if (page < 0 || (long) page * SpaceService.SITEMAP_PAGE_SIZE > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        List<Long> ids = spaces.getSitemapIds(page);
        if (ids.isEmpty() && page > 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        StringBuilder xml = new StringBuilder(XML_HEADER).append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        if (page == 0) xml.append("<url><loc>").append(escape(seo.publicUrl("/spaces"))).append("</loc></url>");
        for (Long id : ids) xml.append("<url><loc>").append(escape(seo.publicUrl("/spaces/" + id))).append("</loc></url>");
        return xml.append("</urlset>").toString();
    }

    private void requirePublicSite() {
        if (!seo.isIndexable()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static String escape(String value) { return HtmlUtils.htmlEscape(value, "UTF-8"); }
}
