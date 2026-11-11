import { useOutletContext, useParams } from 'react-router-dom'
import { Card, Empty } from 'antd'
import CardList from '../CardList'
import { CARD_TYPES, type ProjectContext } from './projectContext'

/** 单类卡片视图:URL 直达,复用 CardList 的编辑/采纳/重生成能力。 */
export default function CardSection() {
  const { type } = useParams()
  const { projectId, detail, refresh } = useOutletContext<ProjectContext>()
  const upper = (type ?? '').toUpperCase()
  const meta = CARD_TYPES.find((t) => t.type === upper)

  if (!meta) {
    return <Empty description="未知卡片类型" />
  }
  const cards = detail.cards.filter((c) => c.type === upper)
  if (cards.length === 0) {
    return <Empty description="该类型卡片尚未生成" />
  }
  return (
    <Card title={`${meta.label}(${cards.length})`}>
      <CardList projectId={projectId} cards={cards} onChanged={refresh} />
    </Card>
  )
}