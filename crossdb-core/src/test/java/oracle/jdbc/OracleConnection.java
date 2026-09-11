package oracle.jdbc;

/**
 * 测试桩：模拟 ojdbc 的 {@code OracleConnection} 接口（仅 Guarded 反射所需的
 * {@link #setIncludeSynonyms}），使无 ojdbc 的测试环境也能断言 CrossDb 对 Oracle
 * 连接开启同义词元数据的行为。若后续测试 classpath 引入真实 ojdbc，类路径二选一
 * 生效且方法签名一致，行为不变。
 */
public interface OracleConnection {

  void setIncludeSynonyms(boolean value);
}
