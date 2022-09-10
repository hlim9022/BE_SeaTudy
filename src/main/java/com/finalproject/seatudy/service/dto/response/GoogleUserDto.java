package com.finalproject.seatudy.service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
public class GoogleUserDto {
    private Long id;
    private String email;
    private String nickname;
    private Long point;
}
