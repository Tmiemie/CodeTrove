package com.codetrove.curator;

import java.util.List;

public interface ReviewSkill {

    String name();

    List<ReviewFindingCandidate> review(ReviewInput input);
}
