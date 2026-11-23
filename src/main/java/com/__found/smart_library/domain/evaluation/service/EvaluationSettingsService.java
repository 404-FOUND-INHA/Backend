package com.__found.smart_library.domain.evaluation.service;

import com.__found.smart_library.domain.evaluation.dto.CriterionWeight;
import com.__found.smart_library.domain.evaluation.dto.EvaluationSettingsResponse;
import com.__found.smart_library.domain.evaluation.dto.EvaluationSettingsUpdateRequest;
import com.__found.smart_library.domain.evaluation.entity.CriterionType;
import com.__found.smart_library.domain.evaluation.entity.EvaluationCriterionWeight;
import com.__found.smart_library.domain.evaluation.entity.EvaluationProfile;
import com.__found.smart_library.domain.evaluation.repository.EvaluationCriterionWeightRepository;
import com.__found.smart_library.domain.evaluation.repository.EvaluationProfileRepository;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import com.__found.smart_library.global.lock.PostgresAdvisoryLock;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 평가 가중치와 폐기 검토 후보 기준 점수. 저장할 때마다 새 버전을 만들고 이전 버전은 비활성화한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EvaluationSettingsService {

    /** 평가 설정 저장용 Advisory Lock 네임스페이스. 다른 기능의 락 키와 겹치지 않게 고정값으로 쓴다. */
    static final int EVALUATION_PROFILE_LOCK_NAMESPACE = 40402;
    private static final String LOCK_KEY = "evaluation-profile";

    /** 저장된 설정이 없을 때의 임시 기본값(모든 평가축 동일 가중치). 팀에서 확정하면 바꾼다. */
    static final BigDecimal DEFAULT_DISPOSAL_THRESHOLD = new BigDecimal("50.00");

    private final EvaluationProfileRepository profileRepository;
    private final EvaluationCriterionWeightRepository weightRepository;
    private final PostgresAdvisoryLock advisoryLock;

    /** 현재 사용 중인 설정. 저장된 적이 없으면 기본값을 돌려준다. */
    public EvaluationSettingsResponse getActive() {
        return profileRepository.findByActiveTrue()
                .map(profile -> EvaluationSettingsResponse.of(profile, loadWeights(profile.getProfileVersion())))
                .orElseGet(this::defaults);
    }

    /**
     * 새 버전으로 저장한다. 락을 먼저 잡아 동시 저장을 순서대로 처리하므로 활성 버전은 항상 1개이고
     * 버전 번호도 겹치지 않는다. 가중치 검증은 락 전에 끝내 불필요하게 대기하지 않는다.
     */
    @Transactional
    public EvaluationSettingsResponse update(EvaluationSettingsUpdateRequest request, Long userId) {
        Map<CriterionType, BigDecimal> weights = validateWeights(request.weights());

        advisoryLock.lock(EVALUATION_PROFILE_LOCK_NAMESPACE, LOCK_KEY);

        profileRepository.deactivateAll();
        weightRepository.deactivateAll();

        String version = nextVersion();
        EvaluationProfile profile = profileRepository.saveAndFlush(EvaluationProfile.activate(
                version, request.disposalThreshold().setScale(2), userId));
        weightRepository.saveAll(weights.entrySet().stream()
                .map(e -> EvaluationCriterionWeight.activate(version, e.getKey(), e.getValue(), userId))
                .toList());
        return EvaluationSettingsResponse.of(profile, toResponse(weights));
    }

    /** v1, v2 ... 중 아직 쓰지 않은 첫 번호. 기존 DB 에서 가져온 버전 이름과 겹치지 않게 존재 여부를 확인한다. */
    private String nextVersion() {
        long n = profileRepository.count() + 1;
        while (profileRepository.existsById("v" + n)) {
            n++;
        }
        return "v" + n;
    }

    /** 모든 평가축이 정확히 한 번씩 있고 합이 1.0 인지 확인하고 평가축 순서대로 정리한다. */
    private Map<CriterionType, BigDecimal> validateWeights(List<CriterionWeight> items) {
        Map<CriterionType, BigDecimal> weights = new EnumMap<>(CriterionType.class);
        for (CriterionWeight item : items) {
            if (weights.put(item.criterion(), item.weight().setScale(4)) != null) {
                throw invalid("평가축이 중복되었습니다: " + item.criterion());
            }
        }
        for (CriterionType type : CriterionType.values()) {
            if (!weights.containsKey(type)) {
                throw invalid("모든 평가축의 가중치가 필요합니다. 누락: " + type);
            }
        }
        BigDecimal sum = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(BigDecimal.ONE) != 0) {
            throw invalid("가중치의 합은 1.0 이어야 합니다. (현재 " + sum.stripTrailingZeros().toPlainString() + ")");
        }
        return weights;
    }

    private List<CriterionWeight> loadWeights(String profileVersion) {
        Map<CriterionType, BigDecimal> weights = new EnumMap<>(CriterionType.class);
        weightRepository.findByProfileVersion(profileVersion)
                .forEach(w -> weights.put(w.getCriterionType(), w.getWeight()));
        return toResponse(weights);
    }

    private EvaluationSettingsResponse defaults() {
        BigDecimal equal = BigDecimal.ONE.divide(BigDecimal.valueOf(CriterionType.values().length))
                .setScale(4);
        Map<CriterionType, BigDecimal> weights = new EnumMap<>(CriterionType.class);
        Arrays.stream(CriterionType.values()).forEach(type -> weights.put(type, equal));
        return EvaluationSettingsResponse.defaults(DEFAULT_DISPOSAL_THRESHOLD, toResponse(weights));
    }

    private List<CriterionWeight> toResponse(Map<CriterionType, BigDecimal> weights) {
        return weights.entrySet().stream()
                .map(e -> new CriterionWeight(e.getKey(), e.getValue()))
                .toList();
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.EVALUATION_INVALID_WEIGHTS, message);
    }
}
