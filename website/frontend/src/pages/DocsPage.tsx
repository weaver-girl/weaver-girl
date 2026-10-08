import { Card, Col, Row, Typography } from "antd";
import { ExportOutlined } from "@ant-design/icons";
import { docLinks } from "../data/content";

const { Title, Paragraph } = Typography;

export function DocsPage() {
  return (
    <>
      <Title level={2}>文档</Title>
      <Paragraph>完整文档随源码维护在仓库的 docs 目录，这里给出入口。</Paragraph>
      <Row gutter={[16, 16]}>
        {docLinks.map((doc) => (
          <Col key={doc.href} xs={24} md={12}>
            <a href={doc.href} target="_blank" rel="noreferrer">
              <Card hoverable title={doc.title} extra={<ExportOutlined />}>
                {doc.description}
              </Card>
            </a>
          </Col>
        ))}
      </Row>
    </>
  );
}
