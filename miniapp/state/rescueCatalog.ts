import type { RescueDataSource } from '../services/rescueDataSource'
import type { PublicRescueItem, PublicRescueStatus } from '../types/rescue'

export interface RescueCatalogSnapshot {
  campusId: string
  status: PublicRescueStatus | ''
  items: PublicRescueItem[]
  page: number
  size: number
  total: number
  loading: boolean
  loadingMore: boolean
  errorMessage: string
  hasMore: boolean
}

const errorText = (error: unknown) => error instanceof Error ? error.message : '公开救助信息暂时无法访问，请稍后重试。'

export class RescueCatalog {
  private campusId = ''
  private status: PublicRescueStatus | '' = ''
  private page = 0
  private items: PublicRescueItem[] = []
  private total = 0
  private loading = false
  private loadingMore = false
  private errorMessage = ''
  private generation = 0

  constructor(private readonly source: RescueDataSource, private readonly size = 3) {}

  selectCampus(campusId: string) {
    if (campusId === this.campusId) return
    this.campusId = campusId
    this.invalidate()
  }

  setStatus(status: PublicRescueStatus | '') {
    if (status === this.status) return
    this.status = status
    this.invalidate()
  }

  refresh() { return this.load(0, false) }

  loadMore() {
    if (!this.campusId || this.loading || this.loadingMore || !this.snapshot.hasMore) return Promise.resolve(this.snapshot)
    return this.load(this.page + 1, true)
  }

  get snapshot(): RescueCatalogSnapshot {
    return { campusId: this.campusId, status: this.status, items: [...this.items], page: this.page, size: this.size,
      total: this.total, loading: this.loading, loadingMore: this.loadingMore, errorMessage: this.errorMessage, hasMore: this.items.length < this.total }
  }

  private async load(page: number, append: boolean) {
    if (!this.campusId) return this.snapshot
    const generation = ++this.generation
    this.loading = !append; this.loadingMore = append; this.errorMessage = ''
    try {
      const result = await this.source.search(this.campusId, this.status, page, this.size)
      if (generation !== this.generation) return this.snapshot
      this.items = append ? [...this.items, ...result.items] : result.items
      this.page = result.page; this.total = result.total
    } catch (error) {
      if (generation === this.generation) this.errorMessage = errorText(error)
    } finally {
      if (generation === this.generation) { this.loading = false; this.loadingMore = false }
    }
    return this.snapshot
  }

  private invalidate() {
    this.generation += 1; this.page = 0; this.items = []; this.total = 0; this.loading = false; this.loadingMore = false; this.errorMessage = ''
  }
}
