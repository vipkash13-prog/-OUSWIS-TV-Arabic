package io.ouswis.tv.standalone;

import java.util.concurrent.atomic.AtomicInteger;

/** True while the viewer is in the live list or a full-screen channel: the background work that asks the provider or the sites waits (same process, :home). */
final class Busy {
    private static final AtomicInteger depth=new AtomicInteger();
    private Busy(){}
    static void enter(){depth.incrementAndGet();}
    static void leave(){if(depth.decrementAndGet()<0)depth.set(0);}
    static boolean on(){return depth.get()>0;}
}
