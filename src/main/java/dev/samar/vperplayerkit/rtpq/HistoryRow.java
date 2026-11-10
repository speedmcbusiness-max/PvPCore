package dev.samar.vperplayerkit.rtpq;

public record HistoryRow(long time, int myScore, int oppScore, int myTier, int oppTier,
                         String myName, String oppName, boolean test) {
}
