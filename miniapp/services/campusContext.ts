import { getPublicAnimalDataSource, isPublicDataApiMode } from './sourceFactory'
import { resolveSelectedCampus } from '../state/campus'

export const resolveCurrentCampusContext = () => resolveSelectedCampus(
  getPublicAnimalDataSource(),
  isPublicDataApiMode(),
)
