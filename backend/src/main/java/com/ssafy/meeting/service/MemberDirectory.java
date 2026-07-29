package com.ssafy.meeting.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 데모용 이름 저장소. 실제 앱에선 member 테이블을 조인해 이름을 붙인다(03 §5-2, 06 §5-2).
 * 여기서는 join 시점에 memberId→이름을 기억해 두고 조회 응답에서 붙여준다.
 */
@Component
public class MemberDirectory {

    private final ConcurrentHashMap<Integer, String> names = new ConcurrentHashMap<>();

    public void remember(int memberId, String name) {
        names.put(memberId, name);
    }

    public String nameOf(int memberId) {
        return names.getOrDefault(memberId, "회원" + memberId);
    }
}
