# 이름 난독화에 집중하고 Spring의 런타임 반사 접근에 필요한 코드는 제거하지 않는다.
-dontshrink
-dontoptimize
-dontwarn
-dontnote
-target 21
-useuniqueclassmembernames
-keeppackagenames com.bedrock.app.**
-keepdirectories
-renamesourcefileattribute SourceFile
-keepattributes Exceptions,InnerClasses,Signature,EnclosingMethod,*Annotation*,MethodParameters,Record

# Boot 진입점, 설정과 메타 애너테이션, HTTP 매핑 경계.
-keep @org.springframework.boot.autoconfigure.SpringBootApplication class * { *; }
-keep @org.springframework.context.annotation.Configuration class * { *; }
-keep @org.springframework.boot.autoconfigure.AutoConfiguration class * { *; }
-keep @org.springframework.web.bind.annotation.RestController class * { *; }
-keep @org.springframework.web.bind.annotation.RestControllerAdvice class * { *; }
-keep @interface com.bedrock.app.** { *; }

# DB 이름, 파생 쿼리, JSON 속성, Redis 세션의 직렬화 이름은 안정적으로 유지한다.
-keep @jakarta.persistence.Entity class * { *; }
-keep @jakarta.persistence.Embeddable class * { *; }
-keep @jakarta.persistence.MappedSuperclass class * { *; }
-keep interface * extends org.springframework.data.repository.Repository { *; }
-keep class com.bedrock.app.**.dto.** { *; }
-keep class * extends java.lang.Record { *; }
-keep class com.bedrock.app.common.auth.SessionPrincipal { *; }
-keep interface com.bedrock.app.common.port.** { *; }
-keep enum com.bedrock.app.** { *; }

# 명시적 이름이 없는 Component의 빈 이름 충돌을 막는다. Service는 이름을 명시한다.
-keepnames @org.springframework.stereotype.Component class *
-keepclassmembers class * {
    @org.springframework.beans.factory.annotation.Autowired *;
    @org.springframework.beans.factory.annotation.Value *;
    @jakarta.annotation.PostConstruct *;
    @jakarta.annotation.PreDestroy *;
    @org.springframework.context.annotation.Bean *;
}
