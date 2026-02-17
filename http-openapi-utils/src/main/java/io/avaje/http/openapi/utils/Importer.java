package io.avaje.http.openapi.utils;

import io.avaje.jsonb.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.XML;

@Json.Import({OpenAPI.class, MediaType.class, Discriminator.class, XML.class})
final class Importer {
}
