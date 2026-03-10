package com.realteeth.imagejob.config

import org.springframework.stereotype.Component

/**
 * Mock Worker API 키를 보관하는 컴포넌트.
 * 환경변수로 주입된 키가 있으면 그 값을 사용하고,
 * 없으면 ApiKeyInitializer가 시작 시 발급 후 설정합니다.
 */
@Component
class ApiKeyProvider(props: AppProperties) {
    var apiKey: String = props.mockWorker.apiKey
}
