package com.spotpulse.controller;

import com.spotpulse.dto.SpaceCreateDto;
import com.spotpulse.dto.SpaceDto;
import com.spotpulse.service.SpaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/spaces")
@RequiredArgsConstructor
public class SpaceMvcController {
    private final SpaceService spaceService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("spaces", spaceService.getAll(Pageable.unpaged()).getContent());
        return "spaces/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("space", new SpaceCreateDto());
        model.addAttribute("isEdit", false);
        return "spaces/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        SpaceDto space = spaceService.getById(id);
        model.addAttribute("space", new SpaceCreateDto(space.getName(), space.getDescription(),
                space.getAddress(), space.getCity(), space.getImageUrl()));
        model.addAttribute("spaceId", id);
        model.addAttribute("isEdit", true);
        return "spaces/form";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("space") SpaceCreateDto space, BindingResult errors, Model model) {
        if (errors.hasErrors()) {
            model.addAttribute("isEdit", false);
            return "spaces/form";
        }
        spaceService.create(space);
        return "redirect:/spaces";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @Valid @ModelAttribute("space") SpaceCreateDto space,
                         BindingResult errors, Model model) {
        spaceService.getById(id);
        if (errors.hasErrors()) {
            model.addAttribute("spaceId", id);
            model.addAttribute("isEdit", true);
            return "spaces/form";
        }
        spaceService.update(id, space);
        return "redirect:/spaces";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id) {
        spaceService.delete(id);
        return "redirect:/spaces";
    }
}
