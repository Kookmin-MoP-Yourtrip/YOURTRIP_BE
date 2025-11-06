package backend.yourtrip.domain.feed.controller;


import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.response.FeedCreateResponse;
import backend.yourtrip.domain.feed.service.FeedService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/feed")
public class FeedController {

    private final FeedService feedService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FeedCreateResponse createFeed(
            @Valid @RequestBody FeedCreateRequest request) {
        return feedService.saveFeed(request);
    }
}
