package com.hmdp.upgrade;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class LocalMediaApi {
    private final LocalMedia media;public LocalMediaApi(LocalMedia media){this.media=media;}
    @PostMapping(value="/v2/media",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Object upload(HttpServletRequest r,@RequestParam String purpose,@RequestParam MultipartFile file){return media.upload(LocalAccountApi.user(r),purpose,file);}
    @GetMapping("/v2/media/{id}") public ResponseEntity<byte[]> read(HttpServletRequest r,@PathVariable String id,@RequestParam(defaultValue="false") boolean thumbnail){return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).header("Cache-Control","no-store").body(media.read(id,LocalAccountApi.optionalUser(r),thumbnail));}
    @PutMapping("/v2/account/avatar") public Object avatar(HttpServletRequest r,@RequestBody Map<String,String> b){return media.avatar(LocalAccountApi.user(r),LocalData.text(b.get("mediaId"),36,36));}
}
