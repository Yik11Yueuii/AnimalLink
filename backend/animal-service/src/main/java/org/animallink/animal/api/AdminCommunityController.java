package org.animallink.animal.api;

import org.animallink.animal.application.CommunityApplicationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/posts")
public class AdminCommunityController {
    private final CommunityApplicationService applicationService;

    public AdminCommunityController(CommunityApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping("/{postId}/hide")
    public CommunityDtos.HiddenPostResponse hide(@PathVariable("postId") String postId) {
        return CommunityDtos.HiddenPostResponse.from(applicationService.hide(postId));
    }
}
