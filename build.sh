#!/bin/bash
# =============================================================================
# Build script for KillBill Plugin
# =============================================================================

set -e  # Exit immediately if a command fails

echo "🚀 Building Killbill Plugin..."

# Clean and build the JAR
mvn clean package -DskipTests -q

JAR_FILE="target/killbill-stripe-plugin-1.0.0.jar"

if [ -f "$JAR_FILE" ]; then
    echo "✅ Build successful!"
    echo "📦 Plugin JAR created: $JAR_FILE"
    echo ""
    echo "📍 Next step: Deploy the JAR to KillBill"
    echo "   cp $JAR_FILE /var/lib/killbill/bundles/plugins/java/__OFFLINE_PAYMENT__/"
else
    echo "❌ Build failed - JAR not found"
    exit 1
fi
