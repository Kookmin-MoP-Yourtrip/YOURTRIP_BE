package backend.yourtrip.global.kakao.dto;

import java.util.List;

public record KakaoSearchResponse(
    List<Document> documents,
    Meta meta
) {

    public record Document(
        String id,
        String place_name,
        String category_name,
        String category_group_code,
        String category_group_name,
        String phone,
        String address_name,
        String road_address_name,
        String x, // longitude
        String y, // latitude
        String place_url,
        String distance
    ) {

    }

    public record Meta(
        int total_count,
        int pageable_count,
        boolean is_end
    ) {

    }
}