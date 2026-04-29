#!/bin/bash
set -euo pipefail

echo "🚀 Deploying Stripe plugin..."

# ==================== SAFETY CHECK ====================
# Set this to your Kill Bill container name
KILLBILL_SERVER="sekai"

# Check if we're running on the correct server
if [[ "$(hostname)" != *"$KILLBILL_SERVER"* ]]; then
    echo "❌ ERROR: This script should only run on the server: '$KILLBILL_SERVER' container."
    echo "Current hostname: $(hostname)"
    echo "Expected hostname to contain: $KILLBILL_SERVER"
    exit 1
fi

JAR_FILE="target/stripe-plugin-8.0.5-SNAPSHOT.jar"

echo "📦 Copying JAR to container..."
docker cp "$JAR_FILE" killbill:/tmp/

echo "📥 Installing plugin via kpm..."
docker exec killbill kpm install_java_plugin killbill-stripe \
  --from-source-file /tmp/stripe-plugin-8.0.5-SNAPSHOT.jar \
  --version 8.0.5-SNAPSHOT \
  --destination /var/lib/killbill/bundles

echo "🔄 Restarting Kill Bill..."
docker restart killbill

echo "⏳ Waiting for killbill container to finish starting..."

while true; do
    STATUS=$(docker ps -f "name=^killbill$" --format "{{.Status}}")
    
    if [[ "$STATUS" != *"(health: starting)"* ]]; then
        echo 
        echo "=================================================================="
        echo "Killbill started."
        echo "📋 Current status:"
        docker ps -f "name=^killbill$" --format "table {{.Names}}\t{{.Status}}\t{{.ID}}"
        echo "=================================================================="
        echo "Done."
        break
    fi
    echo -n "."
    sleep 5
done


echo "✅ Deployment complete!"
