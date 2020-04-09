package nerve.network.quotation.util;

import io.nuls.core.rpc.util.NulsDateUtils;

import java.time.*;
import java.time.format.DateTimeFormatter;

/**
 *
 */
public class TimeUtil {
//    private static final ZoneOffset ZONE = ZoneOffset.of("+8");
    private static final ZoneOffset ZONE_UTC = ZoneOffset.of("Z");
//    private static final String PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final String PATTERN_DATE = "yyyyMMdd";
    private static DateTimeFormatter df = DateTimeFormatter.ofPattern(PATTERN_DATE);

    public static String nowUTCDate() {
        LocalDateTime localDateTime = LocalDateTime.ofEpochSecond(NulsDateUtils.getCurrentTimeSeconds(), 0, ZONE_UTC);
        return localDateTime.format(df);
    }

    public static String theDayBeforeUTCDate(long secondTimestamp) {
        LocalDateTime localDateTime = LocalDateTime.ofEpochSecond(secondTimestamp, 0, ZONE_UTC);
        localDateTime = localDateTime.minusDays(1);
        return localDateTime.format(df);
    }

    public static String toUTCDate(long secondTimestamp){
        Instant instant = Instant.ofEpochSecond(secondTimestamp);
        LocalDateTime localDateTime = LocalDateTime.ofInstant(instant, ZONE_UTC);
        return localDateTime.format(df);
    }


    /**
     * 当前时间是否在当天的指定时段区间内(不包含区间边界)
     * 指定时段必须有开始的时分, 如果没有指定结束时分,则默认指定为当天的24点之前
     * @param starHour 时段开始 时
     * @param starMinute 时段开始 分
     * @param endHour 时段结束 时
     * @param endMinute 时段结束 分
     * @param zone 以指定时区来计算, 默认为UTC
     * @return
     */
    public static boolean isNowDateTimeInRange(int starHour, int starMinute, Integer endHour, Integer endMinute, ZoneOffset zone){
//        LocalDateTime nowDateTime = LocalDateTime.now(zone);
        LocalDateTime nowDateTime = LocalDateTime.ofEpochSecond(NulsDateUtils.getCurrentTimeSeconds(), 0, zone);
//        LocalDate localDate = LocalDate.now(zone);
        LocalDate localDate = Instant.ofEpochSecond(NulsDateUtils.getCurrentTimeSeconds()).atZone(ZONE_UTC).toLocalDate();
        LocalTime startTime = LocalTime.of(starHour, starMinute);
        LocalDateTime startDateTime = LocalDateTime.of(localDate, startTime);
        LocalDateTime endDateTime = null;
        boolean endRs = null == endHour || null == endMinute;
        if(endRs) {
            //如果没有区间结束时间,区间结束时间设置为当天的24点之前
            LocalTime endTime = LocalTime.of(0, 0);
            endDateTime = LocalDateTime.of(localDate.plusDays(1), endTime);
        }else{
            LocalTime endTime = LocalTime.of(endHour, endMinute);
            endDateTime = LocalDateTime.of(localDate, endTime);
        }
        return nowDateTime.isAfter(startDateTime) && nowDateTime.isBefore(endDateTime);
    }

    public static boolean isNowDateTimeInRange(int starHour, int starMinute, int endHour, int endMinute){
        return isNowDateTimeInRange(starHour, starMinute, endHour, endMinute, ZONE_UTC);
    }

    public static boolean isNowDateTimeInRange(int starHour, int starMinute, ZoneOffset ZONE){
        return isNowDateTimeInRange(starHour, starMinute, null, null, ZONE);
    }

    public static boolean isNowDateTimeInRange(int starHour, int starMinute){
        return isNowDateTimeInRange(starHour, starMinute, null, null, ZONE_UTC);
    }
}