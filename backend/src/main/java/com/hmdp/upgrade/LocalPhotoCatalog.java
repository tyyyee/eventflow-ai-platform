package com.hmdp.upgrade;

import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.core.io.ClassPathResource;

/** Fixed licensed sample photography; not user-controlled remote URLs. */
final class LocalPhotoCatalog {
    private static final JsonNode PHOTOS;
    static{try(var in=new ClassPathResource("local-photo-map.json").getInputStream()){PHOTOS=new ObjectMapper().readTree(in);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static String cover(long id){return PHOTOS.path(Long.toString(id)).path("cover").asText();}
    static List<String> photos(long id){var result=new ArrayList<String>();PHOTOS.path(Long.toString(id)).path("photos").forEach(x->result.add(x.asText()));return result;}
}
