package com.finalproject.seatudy.service;

import com.finalproject.seatudy.domain.entity.Member;
import com.finalproject.seatudy.domain.entity.Rank;
import com.finalproject.seatudy.domain.entity.TimeCheck;
import com.finalproject.seatudy.domain.repository.RankRepository;
import com.finalproject.seatudy.domain.repository.TimeCheckRepository;
import com.finalproject.seatudy.security.UserDetailsImpl;
import com.finalproject.seatudy.security.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.finalproject.seatudy.security.exception.ErrorCode.CHECKIN_NOT_TRY;
import static com.finalproject.seatudy.security.exception.ErrorCode.CHECKOUT_NOT_TRY;
import static com.finalproject.seatudy.service.dto.response.TimeCheckListDto.*;
import static com.finalproject.seatudy.service.util.CalendarUtil.*;
import static com.finalproject.seatudy.service.util.Formatter.sdtf;
import static com.finalproject.seatudy.service.util.Formatter.stf;

@Slf4j
@RequiredArgsConstructor
@Service
public class TimeCheckService {

    private final TimeCheckRepository timeCheckRepository;
    private final RankRepository rankRepository;
    private final RankService rankService;

    @Transactional
    public CheckIn checkIn(UserDetailsImpl userDetails) throws ParseException {

        Member member = userDetails.getMember();

        Calendar today = getToday();
        String setToday = dateFormat(today); //날짜 형식에 맞게 String형태로 포맷

        List<TimeCheck> timeChecks = timeCheckRepository.findByMemberAndDate(member,setToday);
        Optional<Rank> rank = rankRepository.findByMemberAndDate(member, setToday);

        //체크아웃을 하지 않은 상태에서 체크인을 시도할 경우
        for (TimeCheck timeCheck : timeChecks) {
            if (timeCheck.getCheckOut() == null) {
                throw new CustomException(CHECKOUT_NOT_TRY);
            }
        }

        String timeWatch = "00:00:00";

        if (rank.isPresent()){
            timeWatch = rank.get().getDayStudy();
        }

        String nowTime = ZonedDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        TimeCheck timeCheck = TimeCheck.builder()
                .member(member)
                .date(setToday)
                .checkIn(nowTime)
                .build();
        timeCheckRepository.save(timeCheck);

        log.info("체크인 {}", timeWatch);

        TimeDetail timeDetail = getTimeDetail(timeWatch);

        CheckIn checkIn = new CheckIn(nowTime, timeWatch, timeDetail);

        return checkIn;
    }

    public TimeCheckDto getCheckIn(UserDetailsImpl userDetails) throws ParseException {

        Member member = userDetails.getMember();

        Calendar today = getToday();
        String setToday = dateFormat(today);

        Optional<Rank> rank = rankRepository.findByMemberAndDate(member, setToday);

        List<TimeCheck> findCheckIn = timeCheckRepository.findByMemberAndDate(member, setToday);

        List<TodayLogDto> todayLogDtos = new ArrayList<>(); // 그 날의 로그 기록

        // 기록이 없을 경우
        if (findCheckIn.size() == 0){
            List<Rank> allMemberList = rankRepository.findAllByMember(member);

            String total = totalTime(allMemberList);

            TimeCheckDto timeCheckDto = new TimeCheckDto("00:00:00", total, false, todayLogDtos);

            log.info("체크인 기록이 없음 {}", member.getEmail());

            return timeCheckDto;
        }

        TimeCheck firstCheckIn = findCheckIn.get(findCheckIn.size()-1); // 체크인을 한번이라도 했을 경우

        Calendar checkInCalendar = Calendar.getInstance();
        String setCheckIn = firstCheckIn.getDate() + " " + firstCheckIn.getCheckIn(); //yyyy-MM-dd HH:mm:ss
        checkInCalendar.setTime(sdtf.parse(setCheckIn)); // checkIn 시간 기준으로 켈린더 셋팅

        String[] timeStamp = firstCheckIn.getCheckIn().split(":"); //시, 분, 초 나누기

        int HH = Integer.parseInt(timeStamp[0]); //시
        int mm = Integer.parseInt(timeStamp[1]); //분
        int ss = Integer.parseInt(timeStamp[2]); //초

        today.add(Calendar.HOUR, -HH);
        today.add(Calendar.MINUTE, -mm);
        today.add(Calendar.SECOND, -ss);

        String dayStudyTime = RankCheck(rank, today); //하루 누적 시간 계산

        List<Rank> allMemberList = rankRepository.findAllByMember(member);

        String total = totalTime(allMemberList);

        for (TimeCheck check : findCheckIn){
            TodayLogDto todayLogDto =  new TodayLogDto(check.getCheckIn(), check.getCheckOut());
            todayLogDtos.add(todayLogDto);
        }

        //체크인, 체크아웃 기록된 세트가 1회 이상 있는 경우
        if (findCheckIn.get(findCheckIn.size() - 1).getCheckOut() != null){
            String todayStudy = rank.get().getDayStudy();
            TimeCheckDto timeCheckDto = new TimeCheckDto(todayStudy, total, false, todayLogDtos);

            log.info("체크인 기록이 1회 이상 있음(timer stop) {}", member.getEmail());
            return timeCheckDto;
        }

        // 현재시간 + 누적시간
        TimeCheckDto timeCheckDto = new TimeCheckDto(dayStudyTime, total, true, todayLogDtos);

        log.info("체크인 기록이 1회 이상 있음(timer continue) {}", member.getEmail());
        return timeCheckDto;
    }

    @Transactional
    public CheckOut checkOut(UserDetailsImpl userDetails) throws ParseException {

        Member member = userDetails.getMember();

        Calendar today = getToday();
        String setToday = dateFormat(today); //날짜 형식에 맞게 String형태로 포맷

        List<TimeCheck> findCheckIns = timeCheckRepository.findByMemberAndDate(member, setToday);
        TimeCheck lastCheckIn = findCheckIns.get(findCheckIns.size() - 1); //마지막번째 기록을 get

        Optional<Rank> findRank = rankRepository.findByMemberAndDate(member, setToday);

        String nowTime = ZonedDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("HH:mm:ss"));

        String dayStudy = getCheckIn(userDetails).getDayStudyTime(); //총 공부시간

        List<Rank> allMemberList = rankRepository.findAllByMember(member);

        String total = totalTime(allMemberList);


        if (lastCheckIn.getCheckOut() != null){
            throw new CustomException(CHECKIN_NOT_TRY);
        }

        //1
        if (rankRepository.findAllByMember(member).size() == 0) {
            total = dayStudy;
        }

        //2
        if (allMemberList.size() != 0 && findRank.isEmpty()) {
            lastCheckIn.setCheckOut(nowTime);
            lastCheckIn.setRank(findCheckIns.get(0).getRank());

            LocalTime dayStudyTime = LocalTime.parse(dayStudy);
            int hh = dayStudyTime.getHour();
            int mm = dayStudyTime.getMinute();
            int ss = dayStudyTime.getSecond();
            int dayStudySecond =  hh * 3600 + mm * 60 + ss; //초으로 환산

            String[] totalArrayFind = total.split(":");
            int HH = Integer.parseInt(totalArrayFind[0]);
            int MM = Integer.parseInt(totalArrayFind[1]);
            int SS = Integer.parseInt(totalArrayFind[2]);
            int totalSecond = HH * 3600 + MM * 60 + SS; //초으로 환산

            int totalTime = dayStudySecond + totalSecond;

            int second = ((totalTime % 3600) % 60);
            int minute = ((totalTime % 3600) / 60);
            int hour = (totalTime / 3600);

            total = String.format("%02d:%02d:%02d",hour,minute,second);
        }

        //3
        if (findRank.isPresent()) {
            lastCheckIn.setCheckOut(nowTime);
            lastCheckIn.setRank(findCheckIns.get(0).getRank());
            findRank.get().setDayStudy(dayStudy);
            total = totalTime(allMemberList);
            findRank.get().setTotalStudy(total);


            log.info("체크아웃 {}", total);

            TimeDetail timeDetail = getTimeDetail(dayStudy);

            CheckOut checkOut = new CheckOut(nowTime, dayStudy, timeDetail);

            return checkOut;
        }

        Calendar cal = rankService.setWeekDate(setToday);

        int week = cal.get(Calendar.WEEK_OF_YEAR)-1;
        if (week == 0){
            week = 53;
        }

        lastCheckIn.setCheckOut(nowTime);
        Rank rank = Rank.builder()
                .dayStudy(dayStudy)
                .totalStudy(total)
                .date(setToday)
                .week(week)
                .member(member)
                .timeChecks(findCheckIns)
                .build();
        lastCheckIn.setRank(rank);
        rankRepository.save(rank);

        TimeDetail timeDetail = getTimeDetail(dayStudy);

        CheckOut checkOut = new CheckOut(nowTime, dayStudy, timeDetail);

        return checkOut;
    }

    private String RankCheck(Optional<Rank> rank, Calendar today) throws ParseException{
        if (rank.isPresent()){
            String dayStudy = stf.format(today.getTime());
            rank.get().getDayStudy();
            Calendar rankDay = todayCalendar(rank.get().getDate());
            String setTime = rank.get().getDate() + " " + rank.get().getDayStudy();
            Date setFormatter = sdtf.parse(setTime);
            rankDay.setTime(setFormatter);

            String[] reTimeStamp = dayStudy.split(":");

            int reHH = Integer.parseInt(reTimeStamp[0]); //시
            int remm = Integer.parseInt(reTimeStamp[1]); //분
            int ress = Integer.parseInt(reTimeStamp[2]); //초

            rankDay.add(Calendar.HOUR, reHH);
            rankDay.add(Calendar.MINUTE, remm);
            rankDay.add(Calendar.SECOND, ress);

            dayStudy = stf.format(rankDay.getTime());
            return dayStudy;
        }
        return stf.format(today.getTime());
    }

    private Calendar getToday() throws ParseException {
        String date = LocalDate.now(ZoneId.of("Asia/Seoul")).toString(); // 현재 서울 날짜

        Calendar setDay = todayCalendar(date); // 오늘 기준 캘린더
        setCalendarTime(setDay); // yyyy-MM-dd 05:00:00(당일 오전 5시) 캘린더에 적용

        Calendar today = todayCalendar(date); // 현재 시간 기준 날짜
        todayCalendarTime(today); // yyyy-MM-dd HH:mm:ss 현재시간

        // compareTo() < 0 : 현재시간이 캘린더보다 작으면(음수) 과거
        if (today.compareTo(setDay) < 0) {
            today.add(Calendar.DATE, -1);  // 오전 5시보다 과거라면, 현재 날짜에서 -1
        }
        return today;
    }

    private TimeDetail getTimeDetail(String time) {
        String[] timeStamp = time.split(":"); //시, 분, 초 나누기

        int HH = Integer.parseInt(timeStamp[0]); //시
        int mm = Integer.parseInt(timeStamp[1]); //분
        int ss = Integer.parseInt(timeStamp[2]); //초

        TimeDetail timeDetail = new TimeDetail(HH, mm, ss);

        return timeDetail;
    }

}
