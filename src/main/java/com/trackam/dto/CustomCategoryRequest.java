package com.trackam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class CustomCategoryRequest {

    @NotBlank
    private String id;

    @NotBlank
    private String name;

    @NotBlank
    private String icon;

    @NotBlank
    private String color;

    @NotBlank
    private String bgColor;

    @NotBlank
    private String dotColor;

    @NotBlank
    @Pattern(regexp = "^(income|expense)$")
    private String type;

    private List<String> keywords;
}
