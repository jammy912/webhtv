package com.fongmi.android.tv.event;

import org.greenrobot.eventbus.EventBus;

public record ServerEvent(Type type, String text, String name, String mark) {

    public static void search(String text) {
        EventBus.getDefault().post(new ServerEvent(Type.SEARCH, text));
    }

    public static void push(String text) {
        EventBus.getDefault().post(new ServerEvent(Type.PUSH, text));
    }

    public static void setting(String text) {
        EventBus.getDefault().post(new ServerEvent(Type.SETTING, text));
    }

    public static void setting(String text, String name) {
        EventBus.getDefault().post(new ServerEvent(Type.SETTING, text, name));
    }

    public static void play(String key, String id, String mark) {
        EventBus.getDefault().post(new ServerEvent(Type.PLAY, id, key, mark));
    }

    private ServerEvent(Type type, String text) {
        this(type, text, "");
    }

    private ServerEvent(Type type, String text, String name) {
        this(type, text, name, "");
    }

    public enum Type {
        SEARCH, PUSH, SETTING, PLAY
    }
}
