import checkers.nullness.quals.*;

// @skip-test
public class PathJoins {

    public void testJoiningMultipleBranches() {
        Object intersect = null;
        if (false) {
          return;
        } else if (intersect==null) {
          return;
        } else {
            intersect = "m";
        }

        intersect.toString();
      }
}
