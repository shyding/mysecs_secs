@echo off
echo ===================================================
echo 开始构建和测试secs4java8字符串混淆
echo ===================================================

echo 1. 清理项目
call mvn clean

echo 2. 编译测试类
call mvn test-compile

echo 3. 运行混淆前的测试
call mvn test -Dtest=StringObfuscationTest

echo 4. 打包项目（不混淆）
call mvn package

echo 5. 使用生产配置文件进行混淆
call mvn package -P production

echo 6. 安装混淆后的jar包到本地Maven仓库
call mvn install -P production

echo ===================================================
echo 混淆完成！
echo 混淆报告位于 target 目录下：
echo - proguard-mapping.txt：原始类名/方法名到混淆后名称的映射
echo - proguard-seeds.txt：未被混淆的类和成员列表
echo - proguard-usage.txt：被移除的未使用的类和成员列表
echo - proguard-configuration.txt：ProGuard使用的完整配置
echo ===================================================

pause
