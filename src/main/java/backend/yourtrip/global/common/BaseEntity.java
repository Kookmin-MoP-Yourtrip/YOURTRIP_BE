package backend.yourtrip.global.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public class BaseEntity {

    /**
     * updatable = false는 방어선이다. 이 컬럼을 정당하게 UPDATE하는 코드는 없으므로
     * Hibernate가 만드는 모든 UPDATE 문에서 아예 빼버린다. detached 인스턴스를 merge해
     * created_at을 null로 덮어썼던 사고(#136)를 한 겹 더 막는다.
     * <p>
     * 스키마에는 영향이 없다 - updatable은 런타임 매핑 속성이라 DDL에 나타나지 않고,
     * 운영의 ddl-auto: validate는 컬럼 존재와 타입만 보므로 수동 DDL이 필요 없다.
     * <p>
     * 다만 이것은 근본 대책이 아니다. merge는 여전히 detached의 null을 메모리상
     * 인스턴스에 복사하므로, DB는 지켜져도 그 세션 동안 getCreatedAt()이 null을 반환할 수
     * 있다. 근본 대책은 detached merge를 쓰지 않는 것이다.
     */
    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

}
