package org.intocps.maestro.webapi.maestro2.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.annotations.ApiModel;

@ApiModel(subTypes = {FixedStepAlgorithmConfig.class, VariableStepAlgorithmConfig.class}, discriminator = "type",
        description = "Simulation algorithm.")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({@JsonSubTypes.Type(value = FixedStepAlgorithmConfig.class, name = "fixed-step"),
        @JsonSubTypes.Type(value = VariableStepAlgorithmConfig.class, name = "var-step")})
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public interface IAlgorithmConfig {
}
