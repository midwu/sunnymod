package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

public class SunnyModClient implements ClientModInitializer {


    @Override
    public void onInitializeClient() {

        System.out.println("hello world!");
    }
}