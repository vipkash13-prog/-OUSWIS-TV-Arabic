package io.ouswis.tv.standalone;

/** Damped spring, like macOS animations: response (s) and damping (1 = no bounce). */
final class Spring {
    float value, velocity, target;
    private final float response, damping;
    private boolean timed;
    private float elapsed,start,lastTarget;
    static Spring focus(float initial){Spring s=new Spring(0.15f,1,initial);s.timed=true;s.lastTarget=initial;return s;}

    Spring(float response, float damping, float initial) {
        this.response = response;
        this.damping = damping;
        value = target = initial;
    }

    void snap(float v) {
        value = target = v;
        velocity = 0;elapsed=0;start=lastTarget=v;
    }

    /** Advances `dt` seconds; returns true while still moving. */
    boolean step(float dt) {
        if(timed){
            if(target!=lastTarget){start=value;lastTarget=target;elapsed=0;}
            if(value==target)return false;
            elapsed+=Math.max(0,dt);
            float t=Math.min(1,(elapsed+0.000001f)/0.15f),e=t*t*(3-2*t);
            value=t>=1?target:start+(target-start)*e;return true;
        }
        if (settled()) {
            value = target;
            velocity = 0;
            return false;
        }
        float omega = (float) (2 * Math.PI / response);
        float stiffness = omega * omega, friction = 2 * damping * omega;
        int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float accel = -stiffness * (value - target) - friction * velocity;
            velocity += accel * h;
            value += velocity * h;
        }
        return true;
    }

    boolean settled() {
        return Math.abs(value - target) < 0.0008f && Math.abs(velocity) < 0.004f;
    }
}
