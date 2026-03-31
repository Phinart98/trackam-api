package com.trackam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class CustomCategoryRequest {

    @NotBlank
    @Size(max = 50)
    private String id;

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Size(max = 100)
    private String icon;

    @NotBlank
    @Size(max = 50)
    private String color;

    @NotBlank
    @Size(max = 50)
    private String bgColor;

    @NotBlank
    @Size(max = 50)
    private String dotColor;

    @NotBlank
    @Pattern(regexp = "^(income|expense)$")
    private String type;

    private List<String> keywords;
}
