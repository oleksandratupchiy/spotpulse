package com.spotpulse.controller;

import com.spotpulse.model.Space;
import com.spotpulse.repository.SpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * MVC-контролер для Thymeleaf-шаблонів (вимога C1).
 * Повертає HTML-сторінки зі списком та формою редагування Space.
 */
@Controller
@RequestMapping("/spaces")
@RequiredArgsConstructor
public class SpaceMvcController {

    private final SpaceRepository spaceRepository;

    /** Список усіх просторів */
    @GetMapping
    public String list(Model model) {
        List<Space> spaces = spaceRepository.findAll();
        model.addAttribute("spaces", spaces);
        return "spaces/list";
    }

    /** Форма створення нового простору */
    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("space", new Space());
        model.addAttribute("isEdit", false);
        return "spaces/form";
    }

    /** Форма редагування існуючого простору */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Space space = spaceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Space not found"));
        model.addAttribute("space", space);
        model.addAttribute("isEdit", true);
        return "spaces/form";
    }

    /** Збереження нового простору (POST) */
    @PostMapping
    public String save(@ModelAttribute Space space) {
        spaceRepository.save(space);
        return "redirect:/spaces";
    }

    /** Оновлення існуючого простору (POST) */
    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @ModelAttribute Space formSpace) {
        Space space = spaceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Space not found"));
        space.setName(formSpace.getName());
        space.setDescription(formSpace.getDescription());
        space.setAddress(formSpace.getAddress());
        space.setCity(formSpace.getCity());
        space.setImageUrl(formSpace.getImageUrl());
        spaceRepository.save(space);
        return "redirect:/spaces";
    }

    /** Видалення простору */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id) {
        spaceRepository.deleteById(id);
        return "redirect:/spaces";
    }
}
