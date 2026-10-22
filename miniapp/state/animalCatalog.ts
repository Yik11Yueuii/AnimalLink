import type { PublicAnimalDataSource } from '../services/dataSource'
import type { AnimalSpecies, AnimalSummaryResponse } from '../types/api'

export interface AnimalCatalogSnapshot {
  campusId: string
  q: string
  species: AnimalSpecies | ''
  page: number
  size: number
  items: AnimalSummaryResponse[]
  total: number
  loading: boolean
  loadingMore: boolean
  errorMessage: string
  hasMore: boolean
}

const errorText = (error: unknown) => error instanceof Error ? error.message : '暂时无法读取 Animal 档案，请稍后重试。'

export class AnimalCatalog {
  private campusId = ''
  private q = ''
  private species: AnimalSpecies | '' = ''
  private page = 0
  private readonly size: number
  private items: AnimalSummaryResponse[] = []
  private total = 0
  private loading = false
  private loadingMore = false
  private errorMessage = ''
  private requestGeneration = 0

  constructor(private readonly source: PublicAnimalDataSource, size = 4) {
    this.size = size
  }

  selectCampus(campusId: string) {
    if (campusId === this.campusId) return
    this.campusId = campusId
    this.resetResults()
  }

  setQuery(q: string) {
    this.q = q.trim()
    this.resetResults()
  }

  setSpecies(species: AnimalSpecies | '') {
    this.species = species
    this.resetResults()
  }

  async refresh() {
    if (!this.campusId) return this.snapshot
    const generation = ++this.requestGeneration
    this.loading = true
    this.loadingMore = false
    this.errorMessage = ''
    const criteria = { campusId: this.campusId, q: this.q, species: this.species, page: 0, size: this.size }
    try {
      const result = await this.source.searchAnimals(criteria)
      if (generation !== this.requestGeneration) return this.snapshot
      this.items = result.items
      this.page = result.page
      this.total = result.total
    } catch (error) {
      if (generation === this.requestGeneration) this.errorMessage = errorText(error)
    } finally {
      if (generation === this.requestGeneration) this.loading = false
    }
    return this.snapshot
  }

  async loadMore() {
    if (!this.campusId || this.loading || this.loadingMore || !this.snapshot.hasMore) return this.snapshot
    const generation = ++this.requestGeneration
    const nextPage = this.page + 1
    this.loadingMore = true
    this.errorMessage = ''
    try {
      const result = await this.source.searchAnimals({
        campusId: this.campusId,
        q: this.q,
        species: this.species,
        page: nextPage,
        size: this.size,
      })
      if (generation !== this.requestGeneration) return this.snapshot
      this.items = [...this.items, ...result.items]
      this.page = result.page
      this.total = result.total
    } catch (error) {
      if (generation === this.requestGeneration) this.errorMessage = errorText(error)
    } finally {
      if (generation === this.requestGeneration) this.loadingMore = false
    }
    return this.snapshot
  }

  get snapshot(): AnimalCatalogSnapshot {
    return {
      campusId: this.campusId,
      q: this.q,
      species: this.species,
      page: this.page,
      size: this.size,
      items: [...this.items],
      total: this.total,
      loading: this.loading,
      loadingMore: this.loadingMore,
      errorMessage: this.errorMessage,
      hasMore: this.items.length < this.total,
    }
  }

  private resetResults() {
    this.requestGeneration += 1
    this.page = 0
    this.items = []
    this.total = 0
    this.loading = false
    this.loadingMore = false
    this.errorMessage = ''
  }
}
