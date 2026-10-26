package com.example.kcapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String apiKey,
        @NotBlank String realmName,
        @Valid @DefaultValue Hash hash) {

    public record Hash(
            @DefaultValue("7168") int memoryKb,
            @DefaultValue("5") int iterations,
            @DefaultValue("1") int parallelism,
            @DefaultValue("32") int hashLength) {
    }
}
