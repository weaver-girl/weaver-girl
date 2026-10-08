import { Button, Card, Col, Row, Space, Typography } from "antd";
import { GithubOutlined } from "@ant-design/icons";
import { Link } from "react-router-dom";
import { CodeBlock } from "../components/CodeBlock";
import { REPO_URL, features, quickStart } from "../data/content";

const { Title, Paragraph, Text } = Typography;

export function HomePage() {
  return (
    <>
      <section className="hero">
        <Text type="secondary">基于 ByteBuddy 的 Java 字节码插桩框架</Text>
        <Title style={{ marginTop: 8, marginBottom: 12 }}>Weaver Girl</Title>
        <Paragraph style={{ fontSize: 18, maxWidth: 720 }}>
          给任意 Java 应用挂上一个 agent，无需改源码即可拦截方法调用、采集调用链与指标。
          灵感来自 SkyWalking 与 OpenTelemetry Java Agent，定位是 APM / IAST 工具的 Hook 底座。
        </Paragraph>
        <Space size="middle" wrap>
          <Link to="/quick-start">
            <Button type="primary" size="large">
              30 秒上手
            </Button>
          </Link>
          <Button size="large" icon={<GithubOutlined />} href={REPO_URL} target="_blank">
            GitHub
          </Button>
        </Space>
        <div className="hero-code">
          <CodeBlock code={"java -javaagent:weaver-girl-agent.jar -jar your-app.jar"} />
        </div>
      </section>

      <section className="section">
        <Title level={2}>能做什么</Title>
        <Row gutter={[16, 16]}>
          {features.map((feature) => (
            <Col key={feature.title} xs={24} md={12} lg={8}>
              <Card title={feature.title} style={{ height: "100%" }}>
                {feature.description}
              </Card>
            </Col>
          ))}
        </Row>
      </section>

      <section className="section">
        <Title level={2}>立刻跑起来</Title>
        <Paragraph>构建 agent 并挂到任何 Java 进程上，16 个内置插件自动发现并开始拦截。</Paragraph>
        <CodeBlock code={quickStart} />
        <Paragraph style={{ marginTop: 16 }}>
          <Link to="/quick-start">查看配置、编程式 API 与注解写法 →</Link>
        </Paragraph>
      </section>
    </>
  );
}
