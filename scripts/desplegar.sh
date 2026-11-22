#!/usr/bin/env bash
# Empaqueta y despliega la función fn-confirmacion-cita (lo mismo que hace el pipeline).
#   ./scripts/desplegar.sh            → compila con Maven, sube el jar a S3 y despliega con CloudFormation
# Requiere credenciales de AWS y que existan los stacks rys-servicios y rys-api.
set -euo pipefail
PROYECTO="${PROYECTO:-rys}"
export AWS_REGION="${AWS_REGION:-us-east-1}" AWS_DEFAULT_REGION="${AWS_REGION:-us-east-1}"
cd "$(dirname "$0")/.."

salida() { aws cloudformation describe-stacks --stack-name "$1" --query "Stacks[0].Outputs[?OutputKey=='$2'].OutputValue" --output text; }

VERSION="${VERSION:-$(git rev-parse --short HEAD 2>/dev/null || date +%Y%m%d%H%M%S)}"
[[ "${SIN_BUILD:-}" == 1 ]] || mvn -B -q clean package
BUCKET=$(salida "${PROYECTO}-servicios" BucketArtefactos)
KEY="fn-confirmacion-cita/${VERSION}.jar"
echo "▶ Subiendo target/fn-confirmacion-cita-*.jar → s3://$BUCKET/$KEY"
aws s3 cp target/fn-confirmacion-cita-1.0.0.jar "s3://$BUCKET/$KEY" --only-show-errors

API_KEY_ID=$(salida "${PROYECTO}-api" ApiKeyFuncionId)
API_KEY=$(aws apigateway get-api-key --api-key "$API_KEY_ID" --include-value --query value --output text)

echo "▶ CloudFormation: ${PROYECTO}-fn-confirmacion-cita (versión $VERSION)"
aws cloudformation deploy --stack-name "${PROYECTO}-fn-confirmacion-cita" --template-file cloudformation/funcion.yaml \
  --no-fail-on-empty-changeset --tags Proyecto="$PROYECTO" Curso=JVY0101-EP3 \
  --parameter-overrides Proyecto="$PROYECTO" CodigoBucket="$BUCKET" CodigoKey="$KEY" ApiKeyValor="$API_KEY"
echo "✔ Función desplegada: $(salida "${PROYECTO}-fn-confirmacion-cita" FuncionArn)"
