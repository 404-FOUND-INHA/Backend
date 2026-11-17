# 배포 가이드 (EC2 + Docker Compose)

EC2 한 대에 Nginx · Spring Boot · PostgreSQL · Redis를 `docker-compose.prod.yml`로 올린다. 프론트엔드는 Vercel이다.

| 용도 | 주소 |
|---|---|
| 프론트엔드 | `https://libsync.cloud` (Vercel) |
| 백엔드 API | `https://api.libsync.cloud` (이 서버) |

외부에 열리는 포트는 nginx의 80/443뿐이다. 백엔드·Postgres·Redis는 컨테이너 내부 네트워크로만 접근한다.

## 0. 사전 조건
- EC2(Ubuntu 24.04, 메모리 2GB 이상), Elastic IP 연결
- 보안그룹: 80, 443은 전체 허용 / 5432, 6379, 8080은 열지 않음 / 22는 아래 참고
  - 자동 배포(9단계)를 쓰면 GitHub Actions 서버의 IP가 계속 바뀌므로 22를 전체(`0.0.0.0/0`) 허용해야 한다. 대신 **키 로그인만 허용**하고(`PasswordAuthentication no`), 개인키는 GitHub Secrets에만 둔다.
  - 자동 배포를 쓰지 않으면 22는 내 IP만 허용하는 것이 가장 안전하다.
- DNS: `api.libsync.cloud` A 레코드가 Elastic IP를 가리킴 (`dig api.libsync.cloud`로 확인)

## 1. 서버 준비 (최초 1회)

```bash
# Docker 설치
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER   # 적용하려면 다시 로그인

# 스왑 2GB (메모리 부족으로 컨테이너가 죽는 것을 막는다)
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

## 2. 코드와 환경변수

```bash
git clone https://github.com/404-FOUND-INHA/Backend.git smart-library
cd smart-library
cp .env.prod.example .env
nano .env
```

`.env`에서 `CHANGE_ME`를 모두 새 값으로 바꾼다. `.env`는 절대 커밋하지 않는다.

```bash
openssl rand -base64 24   # DB_PASSWORD, REDIS_PASSWORD
openssl rand -base64 48   # JWT_SECRET (32바이트 이상)
```

> `spring.profiles.active=dev`를 `.env`에 넣지 않는다. 넣으면 시드 계정(`Password1234!`)이 운영 DB에 들어간다.

## 3. 첫 실행 (HTTP)

`.env`의 `NGINX_CONF=http` 상태로 시작한다.

```bash
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps          # 모두 Up (healthy)인지 확인
docker compose -f docker-compose.prod.yml logs backend | tail -30   # Flyway 적용, Started 확인
curl http://api.libsync.cloud/actuator/health          # {"status":"UP",...}
```

처음 빌드는 몇 분 걸린다.

## 4. HTTPS 적용

Vercel(HTTPS)에서 호출하려면 백엔드도 HTTPS여야 한다. 80 포트가 열려 있고 DNS가 반영된 상태에서 진행한다.

```bash
# 1) 인증서 발급 (Let's Encrypt)
docker compose -f docker-compose.prod.yml run --rm certbot certonly \
  --webroot -w /var/www/certbot -d api.libsync.cloud \
  --email <관리용 이메일> --agree-tos --no-eff-email

# 2) nginx를 HTTPS 설정으로 전환: .env 의 NGINX_CONF=http 를 https 로 바꾼 뒤
docker compose -f docker-compose.prod.yml up -d nginx

# 3) 확인
curl https://api.libsync.cloud/actuator/health
```

### 인증서 자동 갱신
인증서는 90일마다 만료된다. 매주 갱신을 시도하도록 cron을 등록한다 (`crontab -e`).

```cron
0 4 * * 1 cd /home/ubuntu/smart-library && docker compose -f docker-compose.prod.yml run --rm certbot renew --quiet && docker compose -f docker-compose.prod.yml exec -T nginx nginx -s reload
```

잘 되는지는 `docker compose -f docker-compose.prod.yml run --rm certbot renew --dry-run`으로 확인한다.

## 5. 초기 사서 계정
회원가입으로 만들어지는 계정은 항상 `STUDENT_WORKER`다. 가입한 뒤 DB에서 권한을 올린다.

```bash
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U capstone -d smart_library \
  -c "UPDATE app_user SET role = 'LIBRARIAN' WHERE email = '사서이메일@example.com';"
```

## 6. 코드 업데이트 배포

```bash
cd smart-library
git pull
docker compose -f docker-compose.prod.yml up -d --build
```

DB 스키마가 바뀌면(`db/migration`에 새 파일) 백엔드가 시작될 때 Flyway가 자동 적용한다.

## 7. 자주 쓰는 명령

```bash
docker compose -f docker-compose.prod.yml ps                    # 상태
docker compose -f docker-compose.prod.yml logs -f backend       # 백엔드 로그
docker compose -f docker-compose.prod.yml restart backend       # 백엔드 재시작
docker stats --no-stream                                        # 메모리 사용량
```

## 8. DB 백업 (실사용 전에 꼭)
DB가 서버 한 대에 있어서, 인스턴스가 사라지면 데이터도 같이 사라진다.

```bash
# 백업 (실패하면 파일을 남기지 않고, 실행마다 별도 임시 파일을 쓴다)
set -o pipefail
tmp=$(mktemp backup.XXXXXX)
if docker compose -f docker-compose.prod.yml exec -T postgres \
     pg_dump -U capstone smart_library | gzip > "$tmp"; then
  mv "$tmp" "backup_$(date +%F).sql.gz"
else
  rm -f "$tmp"; echo "백업 실패" >&2
fi

# 복구 (빈 DB에). 압축 해제가 끝까지 성공한 경우에만 psql 을 실행한다
# (손상된 백업의 일부만 반영되는 것을 막는다). 오류가 나면 중단하고 전체를 되돌린다.
tmp=$(mktemp restore.XXXXXX)
if gunzip -c backup_2026-01-01.sql.gz > "$tmp" \
   && docker compose -f docker-compose.prod.yml exec -T postgres \
        psql -U capstone -d smart_library -v ON_ERROR_STOP=1 --single-transaction < "$tmp"; then
  echo "복구 완료"
else
  echo "복구 실패" >&2
fi
rm -f "$tmp"
```

cron으로 매일 실행하고 S3 등 서버 밖에 보관한다. 복구가 되는지 한 번은 직접 확인한다.

## 9. 자동 배포 (GitHub Actions)
`main`에 머지되면 `.github/workflows/deploy.yml`이 테스트를 돌린 뒤, EC2에 SSH로 접속해 `git pull` + `docker compose up -d --build`를 실행하고 `https://api.libsync.cloud/actuator/health`가 UP인지 확인한다. PR마다는 `ci.yml`이 테스트만 돌린다.

### 최초 1회 설정
1. **배포 전용 SSH 키 만들기** (내 PC에서, 비밀번호 없이):
   ```bash
   ssh-keygen -t ed25519 -f deploy_key -N "" -C "github-actions-deploy"
   ```
2. **공개키를 서버에 등록**: `deploy_key.pub` 내용을 EC2의 `~/.ssh/authorized_keys` 맨 아래에 한 줄 추가한다.
3. **서버 지문 만들기** (내 PC에서): `ssh-keyscan -t ed25519 <Elastic IP>` 출력 한 줄을 복사한다.
   등록하기 전에 가짜 서버가 아닌지 확인한다. 서버와 내 PC에서 각각 아래를 실행해 `SHA256:...` 값이 같아야 한다.
   ```bash
   ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub                          # EC2 서버에서
   ssh-keyscan -t ed25519 <Elastic IP> 2>/dev/null | ssh-keygen -lf -        # 내 PC에서
   ```
4. **GitHub 저장소 → Settings → Secrets and variables → Actions → New repository secret**에 3개를 등록한다.

   | 이름 | 값 |
   |---|---|
   | `EC2_HOST` | Elastic IP |
   | `EC2_SSH_KEY` | `deploy_key` 파일 전체 내용 (`-----BEGIN` ~ `END-----` 포함) |
   | `EC2_KNOWN_HOSTS` | 3번에서 복사한 한 줄 |
5. 등록이 끝나면 내 PC의 `deploy_key`, `deploy_key.pub` 파일은 삭제한다.

### 동작과 주의
- Actions 탭에서 Deploy 워크플로의 **Run workflow**로 수동 실행도 할 수 있다.
- 서버의 `.env`는 Git에 없으므로 배포가 건드리지 않는다. 환경변수를 바꿀 때는 서버에서 직접 수정하고 `up -d`로 반영한다.
- 배포는 테스트를 통과한 커밋(`github.sha`)만 서버에 반영한다(`git merge --ff-only`). 서버에서 추적 파일을 직접 고쳐 두면 실패하므로 서버에서 코드를 직접 수정하지 않는다.
- 새 버전이 뜨지 못하면 헬스체크 단계가 실패로 표시된다. 이때 서버에는 새 컨테이너가 떠 있지 않을 수 있으므로 `logs backend`로 원인을 확인하고, 필요하면 이전 커밋으로 `git checkout <커밋>` 후 `up -d --build`로 되돌린다. 다음 자동 배포는 먼저 `git switch main`으로 복귀하므로 따로 되돌릴 필요가 없다. 수동으로 `git pull`을 쓸 때는 `git switch main`을 먼저 한다.
- 스키마를 바꾸는 Flyway 마이그레이션이 포함된 배포는 배포 전에 DB 백업(8단계)을 해 둔다.

## 문제 해결
| 증상 | 확인 |
|---|---|
| `up` 시 `.env 에 ... 를 설정하세요` | `.env`에 해당 값이 비어 있다 |
| 백엔드가 `JWT_SECRET 은 32바이트 이상` 오류로 종료 | `JWT_SECRET`이 `CHANGE_ME`이거나 너무 짧다 |
| 브라우저에서 CORS 오류 | `.env`의 `CORS_ALLOWED_ORIGINS`에 프론트 주소가 정확히 있는지(끝에 `/` 없이), 바꿨다면 `up -d`로 재시작 |
| 브라우저에서 mixed content 오류 | 4단계 HTTPS가 적용되지 않았다 |
| nginx가 안 뜸 | `NGINX_CONF=https`인데 인증서가 아직 없다. `http`로 되돌린다 |
| 컨테이너가 계속 죽음 | `docker stats`로 메모리 확인, 스왑이 켜져 있는지 확인 |
