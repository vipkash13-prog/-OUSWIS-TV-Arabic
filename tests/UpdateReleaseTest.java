package io.freemytv;

public final class UpdateReleaseTest {
    public static void main(String[] args) {
        if (UpdateRelease.code("v1.0-official-10040-42351332666-1") != 10040)
            throw new AssertionError("Wrong build from official tag");
        if (UpdateRelease.code("v1.0-official-10041-42351332667-2") <=
                UpdateRelease.code("v1.0-official-10040-42351332666-1"))
            throw new AssertionError("Updates must work while display version stays 1.0");
        for (String bad : new String[] {"v1.0", "v9.0", "arabic-build-123", "v1.0-official-4-1-1",
                "v1.0-official-999999999999999999999-1-1", "v1.0-official-10040-1-1/other"}) {
            try { UpdateRelease.code(bad); throw new AssertionError("Accepted " + bad); }
            catch (IllegalArgumentException expected) {}
        }
        System.out.println("Official release tags and same-version build progression passed.");
    }
}
