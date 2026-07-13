# Action API

## 1. 역할

- Item: 사용자가 만든 실제 데이터 한 건이다.
- Collection: Item이 속하는 그룹이다.
- Block: Item에 들어갈 속성 집합이다. 실행 단계가 아니며 순서대로 실행되지 않는다.
- Action: 하나 이상의 Block을 조합한 Item 생성용 입력 정의다.

Action은 화면 이벤트를 라우팅하지 않는다. 프론트 또는 View 설정이 버튼과 Action ID를 연결하고, 백엔드는 전달받은 Action ID로 입력 폼을 조회하고 실행한다.

한 번의 Action 실행은 여러 Block의 필드를 한 화면에서 입력받아 하나의 Item을 만든다.

## 2. Block 코드

- TODO
- SCHEDULE
- MEMO
- TIMETABLE

displayOrder는 UI 표시 순서일 뿐 실행 순서가 아니다.

## 3. 엔드포인트

- GET /api/v1/actions?scope=available
- GET /api/v1/actions?scope=me
- GET /api/v1/actions?scope=preset
- POST /api/v1/actions
- GET /api/v1/actions/{actionId}
- PATCH /api/v1/actions/{actionId}
- DELETE /api/v1/actions/{actionId}
- POST /api/v1/actions/{actionId}/execute

목록 조회의 scope는 다음 의미를 가진다. 생략하면 available을 사용한다.

- available: 현재 사용자가 만든 Action과 실행 가능한 Preset 전체
- me: 현재 사용자가 만든 비프리셋 Action
- preset: 실행 가능한 Preset 전체

scope는 목록 조회 조건이며 execute 요청에는 포함하지 않는다.

프론트는 View 설정이나 사용자 선택으로 actionId를 확보한 뒤 단건 조회와 실행 API를 호출한다.
Action 생성, 목록, 단건 조회 응답의 id 필드가 이후 조회와 실행 경로에 사용하는 actionId다.

## 4. TODO Action 생성

    {
      "name": "투두 만들기",
      "description": "새 할 일을 만드는 입력 정의",
      "enabled": true,
      "preset": false,
      "blocks": [
        {
          "blockCode": "TODO",
          "displayOrder": 0
        }
      ]
    }

응답의 blocks[].fields는 각 Block의 원본 필드다. inputFields는 여러 Block의 같은 키를 한 번만 남긴 실제 단일 입력 폼 계약이다.

각 inputFields 항목에는 BASIC, SCHEDULE, TODO, NOTE 의미 그룹과 필드가 유래한 sourceBlocks가 함께 들어간다.

TODO Block은 다음 필드를 제공한다.

- title: 필수 텍스트
- dueDate: 선택 날짜, yyyy-MM-dd
- priority: P1, P2, P3, P4, 기본값 P3
- tags: 문자열 배열, 기본값 []
- memo: 선택 Markdown 문자열
- completed: boolean, 기본값 false

## 5. TODO Action 실행

    {
      "targetCollectionId": "835c78c3-8d10-4f26-a40b-9c8cf0599886",
      "prefill": {
        "title": "캡스톤 발표자료 준비"
      },
      "input": {
        "title": "캡스톤 발표자료 준비",
        "dueDate": "2026-04-28",
        "priority": "P1",
        "tags": ["팀플", "회의"],
        "memo": "3분 발표 + 5분 QA 형식으로 확정",
        "completed": false
      }
    }

입력 병합 우선순위는 다음과 같다.

    Block 기본값 < prefill < input

prefill은 빠른 입력 제목이나 캘린더에서 선택한 날짜처럼 입력 화면을 미리 채우는 값이다. GET Action 응답에서 내려오는 값이 아니라 현재 View 문맥을 아는 프론트가 execute 요청에 넣는다. Action을 선택하는 키가 아니다. input은 사용자가 최종 확정한 값이며 같은 필드가 있으면 prefill보다 우선한다.

성공 응답은 당분간 생성된 Item 전체를 포함한다.

    {
      "actionId": "action-uuid",
      "itemId": "item-uuid",
      "status": "COMPLETED",
      "item": {
        "id": "item-uuid",
        "name": "캡스톤 발표자료 준비",
        "ownerId": 17,
        "collectionId": "835c78c3-8d10-4f26-a40b-9c8cf0599886",
        "blockCodes": ["TODO"],
        "attributes": {
          "title": "캡스톤 발표자료 준비",
          "dueDate": "2026-04-28",
          "priority": "P1",
          "tags": ["팀플", "회의"],
          "memo": "3분 발표 + 5분 QA 형식으로 확정",
          "completed": false
        },
        "createdAt": "2026-04-09T12:00:00",
        "updatedAt": "2026-04-09T12:00:00"
      }
    }

생성 후 화면 유지, 이동, 완료 모달, 실행 취소 UI는 Action을 호출한 프론트가 결정한다. itemId로 Item DELETE를 호출해 실행 결과를 취소할 수 있다.

## 6. 프론트 호출 흐름

1. View의 버튼이나 메뉴가 연결된 actionId를 확인한다.
2. GET /api/v1/actions/{actionId}로 입력 폼 정의를 가져온다.
3. blocks와 inputFields를 이용해 하나의 입력 화면을 표시한다.
4. prefill과 사용자의 최종 input을 execute 요청으로 보낸다.
5. 응답의 Item으로 목록과 상세 화면을 갱신한다.

사용자가 View 이벤트와 Action 연결을 직접 편집하는 기능이 필요해지면 Action에 Trigger를 다시 넣지 않고 별도의 ActionBinding(view, event, actionId) 모델을 추가한다.

## 7. 디버깅 기준

별도의 Action 전용 오류 코드나 예외 파일은 두지 않는다. HTTP 상태와 오류 메시지로 원인을 확인한다.

- 400 Bad Request: 잘못된 scope, Block 구성, 알 수 없는 입력 필드, 필수값 누락, 자료형 또는 enum 값 오류
- 403 Forbidden: 다른 사용자의 Action 변경 또는 일반 사용자의 Preset 생성·변경
- 404 Not Found: 현재 사용자가 조회하거나 실행할 수 없는 Action
- 409 Conflict: 비활성 Action 실행

현재 Action 모듈은 별도의 실행 로그를 남기지 않는다.

## 8. 현재 연동 경계

- Action은 targetCollectionId를 Item 생성 경계까지 전달한다.
- 현재 main 소스에는 Collection 도메인과 Repository가 없으므로 Collection 존재 여부와 소유권은 아직 검증하지 않는다.
- Collection 모듈이 합쳐질 때 ItemService.createInCollection에서 존재 여부와 owner/editor 권한을 검증해야 한다.
